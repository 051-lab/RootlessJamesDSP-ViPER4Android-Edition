#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jdsp_header.h"

/* Core entrypoint used by the production processing function pointers. */
extern void JamesDSPProcess(JamesDSPLib *dsp, size_t frames);
static int simulate_broad_reload;

static char stateful[] =
    "@init\ninit_count += 1; ticks = 0; 0[0] = 0; gain = 1;\n"
    "@slider\nslider_count += 1;\n"
    "@block\nblocks += 1; seen_size = samplesblock;\n"
    "@sample\nticks += 1; 0[0] += 1; spl0 = ticks * gain; spl1 = 0[0];\n";

static void load(JamesDSPLib *dsp, int slot, char *script)
{
    char error[256];
    int result = LiveProgStringParserSlot(dsp, slot, script, error, sizeof(error));
    if (result != 1) fprintf(stderr, "slot %d: %d %s\n", slot, result, error);
    assert(result == 1);
    LiveProgEnableSlot(dsp, slot);
}

/* Enumerate only: regvar would silently create missing variables and mask errors. */
typedef struct { const char *name; float value; int found; } Lookup;
static int32_t find(const char *name, float *value, void *opaque)
{
    Lookup *lookup = opaque;
    if (!strcmp(name, lookup->name)) {
        lookup->value = *value;
        lookup->found = 1;
        return 0;
    }
    return 1;
}
static float value(JamesDSPLib *dsp, int slot, const char *name)
{
    Lookup lookup = {name, 0, 0};
    NSEEL_VM_enumallvars(LiveProgGetSlot(dsp, slot)->vm, find, &lookup);
    assert(lookup.found);
    return lookup.value;
}
static void process(JamesDSPLib *dsp, int slot, size_t frames)
{
    memset(dsp->tmpBuffer[0], 0, frames * sizeof(float));
    memset(dsp->tmpBuffer[1], 0, frames * sizeof(float));
    LiveProgProcessSlot(dsp, slot, frames);
}

static void isolation(JamesDSPLib *dsp)
{
    for (int slot = 0; slot < 4; slot++) {
        load(dsp, slot, stateful);
        process(dsp, slot, 8);
        assert(dsp->tmpBuffer[0][7] == 8);
        assert(dsp->tmpBuffer[1][7] == 8);
        assert(value(dsp, slot, "init_count") == 1);
        assert(value(dsp, slot, "slider_count") == 1);
        assert(value(dsp, slot, "blocks") == 1);
        assert(value(dsp, slot, "seen_size") == 8);
    }
    for (int target = 0; target < 4; target++) {
        float ticks[4], sliders[4], blocks[4];
        for (int slot = 0; slot < 4; slot++) {
            ticks[slot] = value(dsp, slot, "ticks");
            sliders[slot] = value(dsp, slot, "slider_count");
            blocks[slot] = value(dsp, slot, "blocks");
        }
        assert(LiveProgSetVariableSlot(dsp, target, "gain", 2));
        if (simulate_broad_reload)
            for (int slot = 0; slot < 4; slot++) load(dsp, slot, stateful);
        for (int slot = 0; slot < 4; slot++) {
            assert(value(dsp, slot, "ticks") == ticks[slot]);
            assert(value(dsp, slot, "init_count") == 1);
            assert(value(dsp, slot, "slider_count") == sliders[slot] + (slot == target));
            process(dsp, slot, 3);
            assert(dsp->tmpBuffer[1][2] == ticks[slot] + 3);
            assert(dsp->tmpBuffer[0][2] == (ticks[slot] + 3) * value(dsp, slot, "gain"));
            assert(value(dsp, slot, "blocks") == blocks[slot] + 1);
            assert(value(dsp, slot, "seen_size") == 3);
        }
        load(dsp, target, stateful);
        for (int slot = 0; slot < 4; slot++) {
            process(dsp, slot, 1);
            assert(dsp->tmpBuffer[1][0] == (slot == target ? 1 : ticks[slot] + 4));
        }
    }
    assert(!LiveProgSetVariableSlot(dsp, -1, "gain", 1));
    assert(!LiveProgSetVariableSlot(dsp, 4, "gain", 1));
    assert(!LiveProgSetVariableSlot(dsp, 0, "missing", 1));
    assert(!LiveProgSetVariableSlot(dsp, 0, "gain", NAN));
    puts("PASS: four-slot state/RAM isolation, targeted parameter update and reload lifecycle");
}

static void failures(JamesDSPLib *dsp)
{
    char *bad[] = {"@init\nx = ;\n@sample\nspl0 = 0;", "@init\nx = 0;",
        "@sample\nspl0 = ;", "@slider\nx = ;\n@sample\nspl0 = 0;",
        "@block\nx = ;\n@sample\nspl0 = 0;", "@sample\nspl0 = 0;\n@sample\nspl0 = 0;"};
    for (int target = 0; target < 4; target++) {
        for (int error = 0; error < 6; error++) {
            float ticks[4];
            for (int slot = 0; slot < 4; slot++) ticks[slot] = value(dsp, slot, "ticks");
            char message[256];
            assert(LiveProgStringParserSlot(dsp, target, bad[error], message, sizeof(message)) == -error - 1);
            for (int slot = 0; slot < 4; slot++) {
                process(dsp, slot, 1);
                assert(dsp->tmpBuffer[1][0] == ticks[slot] + 1);
            }
        }
    }
    puts("PASS: all six section failures preserve every active program and its RAM");
}

static void gaps_and_order(JamesDSPLib *dsp)
{
    LiveProgDestructor(dsp);
    LiveProgConstructor(dsp);
    for (int slot = 0; slot < 4; slot++) LiveProgDisableSlot(dsp, slot);
    load(dsp, 0, "@sample\nspl0 += 1; spl1 += 1;");
    load(dsp, 3, "@init\ngain = 2;\n@sample\nspl0 *= gain; spl1 *= gain;");
    int order[] = {JDSP_EFX_LIVEPROG, JDSP_EFX_LIVEPROG2, JDSP_EFX_LIVEPROG3, JDSP_EFX_LIVEPROG4};
    JamesDSPSetChainOrder(dsp, order, 4);
    dsp->tmpBuffer[0][0] = dsp->tmpBuffer[1][0] = 1;
    dsp->processInternal(dsp, 1);
    assert(dsp->tmpBuffer[0][0] == 4);
    int reversed[] = {JDSP_EFX_LIVEPROG4, JDSP_EFX_LIVEPROG3, JDSP_EFX_LIVEPROG2, JDSP_EFX_LIVEPROG};
    JamesDSPSetChainOrder(dsp, reversed, 4);
    assert(LiveProgSetVariableSlot(dsp, 3, "gain", 3));
    dsp->tmpBuffer[0][0] = dsp->tmpBuffer[1][0] = 1;
    dsp->processInternal(dsp, 1);
    assert(dsp->tmpBuffer[0][0] == 4); /* 1 * 3 + 1, not (1 + 1) * 3 */
    assert(!LiveProgSetVariableSlot(dsp, 1, "gain", 3));
    puts("PASS: gapped 0/3 configuration, optional init/slider, stable identity after reordering");
}

static int sample_rate(JamesDSPLib *dsp, int require_propagation)
{
    char script[] = "@init\ninitial_rate = srate;\n@slider\nslider_rate = srate;\n"
        "@block\nblock_rate = srate;\n@sample\nspl0 = srate; spl1 = initial_rate;";
    for (int slot = 0; slot < 4; slot++) load(dsp, slot, script);
    JamesDSPSetSampleRate(dsp, 44100, 0);
    int stale = 0;
    for (int slot = 0; slot < 4; slot++) {
        process(dsp, slot, 1);
        if (dsp->tmpBuffer[0][0] != 44100) stale++;
        assert(value(dsp, slot, "initial_rate") == 48000);
        assert(value(dsp, slot, "slider_rate") == 48000);
    }
    printf("KNOWN GAP: sample-rate setter leaves %d/4 populated VMs stale\n", stale);
    if (require_propagation) return stale ? 1 : 0;
    assert(stale == 4); /* Characterization, NOT the desired acceptance contract. */
    for (int slot = 0; slot < 4; slot++) {
        LiveProgEnableSlot(dsp, slot);
        process(dsp, slot, 1);
        assert(dsp->tmpBuffer[0][0] == 44100);
        assert(value(dsp, slot, "block_rate") == 44100);
        assert(value(dsp, slot, "initial_rate") == 48000);
        assert(value(dsp, slot, "slider_rate") == 48000);
    }
    puts("PASS: re-enable refreshes srate without running init or slider");
    for (int slot = 1; slot <= 2; slot++) {
        LiveProgDestructorSlot(dsp, slot);
        LiveProgConstructorSlot(dsp, slot);
        LiveProgDisableSlot(dsp, slot);
    }
    JamesDSPSetSampleRate(dsp, 48000, 0);
    for (int slot = 0; slot < 4; slot += 3) {
        process(dsp, slot, 1);
        assert(dsp->tmpBuffer[0][0] == 44100);
        assert(value(dsp, slot, "initial_rate") == 48000);
    }
    assert(!LiveProgSetVariableSlot(dsp, 1, "srate", 48000));
    puts("PASS: reverse rate transition characterizes stale state with empty middle slots");
    return 0;
}

int main(int argc, char **argv)
{
    int require_propagation = argc == 2 && !strcmp(argv[1], "--require-rate-propagation");
    simulate_broad_reload = argc == 2 && !strcmp(argv[1], "--simulate-broad-reload");
    JamesDSPLib *dsp = calloc(1, sizeof(*dsp));
    assert(dsp);
    JamesDSPGlobalMemoryAllocation();
    if (argc == 2 && !strcmp(argv[1], "--full-engine-init")) {
        /* Separate acceptance probe: currently exposes inherited filterbank alignment UB. */
        JamesDSPInit(dsp, 64, 48000);
        JamesDSPFree(dsp);
        JamesDSPGlobalMemoryDeallocation();
        free(dsp);
        return 0;
    }
    /* Only LiveProg is constructed. No mocked EEL, locks, rate setter or chain dispatch. */
    dsp->fs = dsp->trueSampleRate = 48000;
    dsp->blockSize = dsp->blockSizeMax = 64;
    assert(pthread_mutex_init(&dsp->m_in_processing, NULL) == 0);
    dsp->isMutexSuccess = 1;
    dsp->tmpBuffer[0] = calloc(128, sizeof(float));
    assert(dsp->tmpBuffer[0]);
    dsp->tmpBuffer[1] = dsp->tmpBuffer[0] + 64;
    dsp->postGain = 1;
    dsp->processInternal = JamesDSPProcess;
    LiveProgConstructor(dsp);
    int result = 0;
    if (argc == 2 && !strcmp(argv[1], "--vm-allocation-only")) {
        for (int slot = 0; slot < 4; slot++) {
            assert(value(dsp, slot, "srate") == 48000);
            assert(value(dsp, slot, "samplesblock") == 0);
        }
        puts("PASS: all four VM variable tables allocated and enumerated under UBSan");
        goto cleanup;
    }
    assert(JamesDSPGetMutexStatus(dsp));
    dsp->limiter.mode = 2;
    isolation(dsp);
    failures(dsp);
    gaps_and_order(dsp);
    result = sample_rate(dsp, require_propagation);
cleanup:
    LiveProgDestructor(dsp);
    free(dsp->tmpBuffer[0]);
    for (int i = 0; i < 3; i++) {
        free(dsp->blobsCh1[i]); free(dsp->blobsCh2[i]);
        free(dsp->blobsCh3[i]); free(dsp->blobsCh4[i]);
    }
    for (int i = 0; i < 4; i++) free(dsp->hrtfblobsResampled[i]);
    pthread_mutex_destroy(&dsp->m_in_processing);
    JamesDSPGlobalMemoryDeallocation();
    free(dsp);
    return result;
}
