#include "aec.h"

#include <complex.h>
#include <fftw3.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define N AEC_N
#define K (N + 1)                  // bins of a 2N real FFT
#define P ((AEC_TAPS_MS * 48 + N - 1) / N)

// Kalman model: the echo path drifts slowly (A close to 1); PHI_SMOOTH sets how fast the
// estimate of "what the mic holds besides echo" follows the error.
#define A2 (0.9998f * 0.9998f)
#define PHI_SMOOTH 0.5f
#define P_INIT 1e-4f

struct aec {
    fftwf_plan fwd, inv;
    float* tbuf;                   // 2N, time domain scratch
    fftwf_complex* fbuf;           // K, frequency domain scratch
    float prev[N];                 // previous reference block (overlap-save)
    float complex X[P][K];         // reference spectra, newest first (circular)
    float complex W[P][K];         // echo path, by lag: W[p] pairs with the p-th newest X
    float Pc[P][K];                // state uncertainty, by lag
    float phi[K];                  // power the echo model can't explain
    int head;                      // index of the newest partition in X
    int rr;                        // partition whose gradient constraint is due
};

aec* aec_new(void) {
    aec* a = fftwf_malloc(sizeof(*a));
    if (!a)
        return NULL;
    memset(a, 0, sizeof(*a));
    a->tbuf = fftwf_alloc_real(2 * N);
    a->fbuf = fftwf_alloc_complex(K);
    a->fwd = fftwf_plan_dft_r2c_1d(2 * N, a->tbuf, a->fbuf, FFTW_MEASURE);
    a->inv = fftwf_plan_dft_c2r_1d(2 * N, a->fbuf, a->tbuf, FFTW_MEASURE);
    aec_reset(a);
    return a;
}

void aec_free(aec* a) {
    if (!a)
        return;
    fftwf_destroy_plan(a->fwd);
    fftwf_destroy_plan(a->inv);
    fftwf_free(a->tbuf);
    fftwf_free(a->fbuf);
    fftwf_free(a);
}

void aec_reset(aec* a) {
    memset(a->prev, 0, sizeof(a->prev));
    memset(a->X, 0, sizeof(a->X));
    memset(a->W, 0, sizeof(a->W));
    memset(a->phi, 0, sizeof(a->phi));
    for (int p = 0; p < P; p++)
        for (int k = 0; k < K; k++)
            a->Pc[p][k] = P_INIT;
    a->head = 0;
    a->rr = 0;
}

static inline int part(const aec* a, int p) { // p-th newest partition
    int i = a->head - p;
    return i < 0 ? i + P : i;
}

void aec_predict(aec* a, const float* ref, float* pred) {
    memcpy(a->tbuf, a->prev, N * sizeof(float));
    memcpy(a->tbuf + N, ref, N * sizeof(float));
    memcpy(a->prev, ref, N * sizeof(float));
    fftwf_execute(a->fwd);
    a->head = (a->head + 1) % P;
    float complex* x0 = a->X[a->head];
    memcpy(x0, a->fbuf, K * sizeof(float complex));

    float complex Y[K] = { 0 };
    for (int p = 0; p < P; p++) {
        const float complex* x = a->X[part(a, p)];
        const float complex* w = a->W[p];
        for (int k = 0; k < K; k++)
            Y[k] += w[k] * x[k];
    }
    memcpy(a->fbuf, Y, sizeof(Y));
    fftwf_execute(a->inv);
    const float scale = 1.0f / (2 * N);
    for (int i = 0; i < N; i++)
        pred[i] = a->tbuf[N + i] * scale;
}

void aec_update(aec* a, const float* err, bool learn) {
    if (!learn)
        return;
    memset(a->tbuf, 0, N * sizeof(float));
    memcpy(a->tbuf + N, err, N * sizeof(float));
    fftwf_execute(a->fwd);
    float complex E[K];
    memcpy(E, a->fbuf, sizeof(E));

    float R[K];
    for (int k = 0; k < K; k++) {
        float e2 = crealf(E[k]) * crealf(E[k]) + cimagf(E[k]) * cimagf(E[k]);
        a->phi[k] = PHI_SMOOTH * a->phi[k] + (1 - PHI_SMOOTH) * e2;
        R[k] = a->phi[k];
    }
    for (int p = 0; p < P; p++) {
        const float complex* x = a->X[part(a, p)];
        const float* pc = a->Pc[p];
        for (int k = 0; k < K; k++)
            R[k] += 0.5f * (crealf(x[k]) * crealf(x[k]) + cimagf(x[k]) * cimagf(x[k])) * pc[k];
    }
    for (int p = 0; p < P; p++) {
        const float complex* x = a->X[part(a, p)];
        float complex* w = a->W[p];
        float* pc = a->Pc[p];
        for (int k = 0; k < K; k++) {
            float x2 = crealf(x[k]) * crealf(x[k]) + cimagf(x[k]) * cimagf(x[k]);
            float mu = pc[k] / (R[k] + 1e-20f);
            w[k] += mu * conjf(x[k]) * E[k];
            float w2 = crealf(w[k]) * crealf(w[k]) + cimagf(w[k]) * cimagf(w[k]);
            pc[k] = A2 * (1 - 0.5f * mu * x2) * pc[k] + (1 - A2) * w2;
        }
    }

    // Gradient constraint, one partition per block: keep each partition's response causal and
    // N long, which the frequency-domain update alone doesn't.
    float complex* w = a->W[a->rr];
    memcpy(a->fbuf, w, K * sizeof(float complex));
    fftwf_execute(a->inv);
    const float scale = 1.0f / (2 * N);
    for (int i = 0; i < N; i++)
        a->tbuf[i] *= scale;
    memset(a->tbuf + N, 0, N * sizeof(float));
    fftwf_execute(a->fwd);
    memcpy(w, a->fbuf, K * sizeof(float complex));
    a->rr = (a->rr + 1) % P;
}

int aec_find_delay(const float* ref, const float* mic, int n, int max_lag, float* confidence) {
    int m = 2 * n, kb = m / 2 + 1;
    float* t = fftwf_alloc_real(m);
    fftwf_complex* R = fftwf_alloc_complex(kb);
    fftwf_complex* M = fftwf_alloc_complex(kb);
    fftwf_plan pr = fftwf_plan_dft_r2c_1d(m, t, R, FFTW_ESTIMATE);
    fftwf_plan pm = fftwf_plan_dft_r2c_1d(m, t, M, FFTW_ESTIMATE);
    fftwf_plan pi = fftwf_plan_dft_c2r_1d(m, R, t, FFTW_ESTIMATE);
    memset(t, 0, m * sizeof(float));
    memcpy(t, ref, n * sizeof(float));
    fftwf_execute(pr);
    memset(t, 0, m * sizeof(float));
    memcpy(t, mic, n * sizeof(float));
    fftwf_execute(pm);
    // Phase transform over 200 Hz - 6 kHz: a sharp peak at the delay, whatever the spectrum.
    for (int k = 0; k < kb; k++) {
        double f = (double)k * 48000 / m;
        float complex g = ((float complex*)M)[k] * conjf(((float complex*)R)[k]);
        float mag = cabsf(g);
        ((float complex*)R)[k] = (f < 200 || f > 6000 || mag < 1e-20f) ? 0 : g / mag;
    }
    fftwf_execute(pi);
    int best = 0;
    double sum = 0;
    for (int i = 0; i < max_lag; i++) {
        sum += fabsf(t[i]);
        if (t[i] > t[best])
            best = i;
    }
    float q = (float)(t[best] / (sum / max_lag + 1e-30));
    if (confidence)
        *confidence = q;
    fftwf_destroy_plan(pr);
    fftwf_destroy_plan(pm);
    fftwf_destroy_plan(pi);
    fftwf_free(t);
    fftwf_free(R);
    fftwf_free(M);
    return q > 6 ? best : -1;
}
