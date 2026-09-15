#include <assert.h>
#include <string.h>
#include <stdio.h>
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

typedef struct { int failWrite, failSeek; } Sink;
static size_t write_sink(void *user, const void *data, size_t bytes)
{
    (void)data;
    return ((Sink*)user)->failWrite ? 0 : bytes;
}
static drwav_bool32 seek_sink(void *user, int offset, drwav_seek_origin origin)
{
    (void)offset; (void)origin;
    return !((Sink*)user)->failSeek;
}

int main(void)
{
    drwav_data_format format = { drwav_container_riff, DR_WAVE_FORMAT_IEEE_FLOAT, 2, 48000, 32 };
    const float samples[] = { .25f, -.5f, .75f, 1.f };
    drwav wav;
    void *memory = NULL;
    size_t size = 0;
    assert(drwav_init_memory_write(&wav, &memory, &size, &format, NULL));
    assert(drwav_write_pcm_frames(&wav, 2, samples) == 2);
    assert(drwav_uninit(&wav) == DRWAV_SUCCESS);
    assert(drwav_init_memory(&wav, memory, size, NULL));
    float output[4];
    assert(drwav_read_pcm_frames_f32(&wav, 2, output) == 2);
    assert(memcmp(samples, output, sizeof(samples)) == 0);
    assert(drwav_uninit(&wav) == DRWAV_SUCCESS);
    free(memory);
    for (int w64 = 0; w64 < 2; ++w64) {
        format.container = w64 ? drwav_container_w64 : drwav_container_riff;
        for (int failure = 0; failure < 2; ++failure) {
            Sink sink = {0};
            assert(drwav_init_write(&wav, &format, write_sink, seek_sink, &sink, NULL));
            assert(drwav_write_pcm_frames(&wav, 2, samples) == 2);
            sink.failWrite = failure == 0;
            sink.failSeek = failure == 1;
            assert(drwav_uninit(&wav) == DRWAV_IO_ERROR);
        }
    }
    puts("WAV round-trip and RIFF/W64 finalization failures passed");
    return 0;
}
