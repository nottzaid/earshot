// Offline check of aec.c on a capture from tools/aec-capture.sh (L, R, mic at 48 kHz, f32).
// Writes mic and output as interleaved f32 pairs, the format tools/aec_proto.py --save uses.
#include "aec.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char** argv) {
    if (argc < 3)
        return fprintf(stderr, "usage: aec-test capture.wav out.f32\n"), 2;
    FILE* f = fopen(argv[1], "rb");
    if (!f)
        return 1;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    rewind(f);
    char* d = malloc(size);
    if (fread(d, 1, size, f) != (size_t)size)
        return 1;
    char* data = memmem(d, size, "data", 4);
    float* x = (float*)(data + 8);
    long n = (size - (data + 8 - d)) / 12;
    float* ref = malloc(n * sizeof(float));
    float* mic = malloc(n * sizeof(float));
    for (long i = 0; i < n; i++) {
        ref[i] = 0.5f * (x[3 * i] + x[3 * i + 1]);
        mic[i] = x[3 * i + 2];
    }
    // bulk delay from 2 s of audio starting at 4 s, as the receiver would
    float q;
    int at = argc > 3 ? atoi(argv[3]) * 48000 : 4 * 48000;
    int lag = aec_find_delay(ref + at, mic + at, 1 << 17, 12000, &q);
    fprintf(stderr, "bulk delay %d samples (%.3f ms), confidence %.1f\n", lag, lag / 48.0, q);
    if (lag < 0)
        return 1;
    int D = lag > AEC_MARGIN ? lag - AEC_MARGIN : 0;
    aec* a = aec_new();
    FILE* o = fopen(argv[2], "wb");
    float pred[AEC_N], err[AEC_N];
    for (long b = 0; b + AEC_N <= n; b += AEC_N) {
        float r[AEC_N];
        for (int i = 0; i < AEC_N; i++)
            r[i] = b + i - D >= 0 ? ref[b + i - D] : 0;
        aec_predict(a, r, pred);
        float e = 0;
        for (int i = 0; i < AEC_N; i++) {
            err[i] = mic[b + i] - pred[i];
            e += r[i] * r[i];
        }
        aec_update(a, err, e > AEC_N * 1e-7f);
        for (int i = 0; i < AEC_N; i++) {
            float pair[2] = { mic[b + i], err[i] };
            fwrite(pair, sizeof(float), 2, o);
        }
    }
    fclose(o);
    aec_free(a);
    return 0;
}
