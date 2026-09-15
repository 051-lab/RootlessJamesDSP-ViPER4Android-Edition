/* Exercises the actual EEL builtins; unused compiler sections are link-discarded. */
#include "nseel-compiler.c"

int main(void)
{
    uint64_t converted;
    assert(!_eel_floatToUint64(0x1p64f, &converted));
    compileContext *ctx = calloc(1, sizeof(*ctx));
    assert(ctx);
    void *strings[] = { s_str_create_from_c_str("/tmp/rjdsp-wav-bounds.wav") };
    eel_builtin_memRegion region = {0};
    region.memRegion = strings;
    region.slot = 1;
    ctx->region_context = &region;
    float destination = 0, channels = 1, rate = 48000, frames = 2;
    float offset = NSEEL_RAM_ITEMSPERBLOCK - 1;
    float *args[] = { &destination, &channels, &rate, &frames, &offset };
    assert(_eel_writeWavMemory(ctx, 5, args) == -1);
    assert(_eel_writeWavFile(ctx, 5, args) == -1);
    const float invalidOffsets[] = { -1, NSEEL_RAM_ITEMSPERBLOCK, INFINITY, NAN };
    for (size_t i = 0; i < sizeof(invalidOffsets) / sizeof(invalidOffsets[0]); ++i) {
        offset = invalidOffsets[i];
        assert(_eel_writeWavMemory(ctx, 5, args) == -1);
        assert(_eel_writeWavFile(ctx, 5, args) == -1);
    }
    offset = NSEEL_RAM_ITEMSPERBLOCK - 1;
    frames = 1;
    assert(_eel_writeWavMemory(ctx, 5, args) == 1);
    s_str_destroy(&strings[0]);
    free(ctx);
    puts("WAV bounds checks passed");
    return 0;
}
