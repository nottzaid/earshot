#include "aec.h"

#include <complex.h>
#include <fftw3.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define N AEC_N
#define K (N + 1)                  // bins of a 2N real FFT
#define P AEC_P

// Kalman model: the echo path drifts slowly (A close to 1); PHI_SMOOTH sets how fast the
// estimate of "what the mic holds besides echo" follows the error.
#define A2 (0.9998f * 0.9998f)
#define PHI_SMOOTH 0.5f
#define P_INIT 1e-4f
#define GUARD_SMOOTH 0.97f         // per block: the louder-than-the-mic test looks at ~90 ms
#define GUARD_MIN_BLOCKS 16        // and judges only after ~40 ms of it
#define SHARE_SMOOTH 0.97f         // per block: the share of the prediction to subtract follows ~90 ms
#define SHARE_MAX 16.0f            // up to 24 dB more than predicted
#define GOOD_BLOCKS 64             // keep the path as good after this many blocks (~170 ms) cancelling 6 dB

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
    float pred[N];                 // the last prediction, to tell what the mic held
    float mic_pow, out_pow;        // smoothed block power before and after subtraction
    int guard_n;                   // blocks those have seen since the last reset
    float mp, pp;                  // smoothed mic x prediction and prediction power
    float share;                   // of the prediction to subtract: least squares over mp, pp
    int partial;                   // blocks until the speaker history is whole again
    float complex G[P][K];         // the last echo path that cancelled well, as W
    bool have_good;
    int good_n;                    // blocks cancelling well since G was last kept
    float* at;                     // aec_align: 2 AEC_ALIGN, time domain
    fftwf_complex *af, *bf;        // its spectra
    fftwf_plan apf, bpf, api;
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
    a->at = fftwf_alloc_real(2 * AEC_ALIGN);
    a->af = fftwf_alloc_complex(AEC_ALIGN + 1);
    a->bf = fftwf_alloc_complex(AEC_ALIGN + 1);
    a->apf = fftwf_plan_dft_r2c_1d(2 * AEC_ALIGN, a->at, a->af, FFTW_MEASURE);
    a->bpf = fftwf_plan_dft_r2c_1d(2 * AEC_ALIGN, a->at, a->bf, FFTW_MEASURE);
    a->api = fftwf_plan_dft_c2r_1d(2 * AEC_ALIGN, a->af, a->at, FFTW_MEASURE);
    aec_reset(a);
    return a;
}

void aec_free(aec* a) {
    if (!a)
        return;
    fftwf_destroy_plan(a->fwd);
    fftwf_destroy_plan(a->inv);
    fftwf_destroy_plan(a->apf);
    fftwf_destroy_plan(a->bpf);
    fftwf_destroy_plan(a->api);
    fftwf_free(a->tbuf);
    fftwf_free(a->fbuf);
    fftwf_free(a->at);
    fftwf_free(a->af);
    fftwf_free(a->bf);
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
    a->mic_pow = a->out_pow = 0;
    a->guard_n = 0;
    a->mp = a->pp = a->share = 0;
    a->partial = P;
    a->have_good = false;
    a->good_n = 0;
}

void aec_forget(aec* a) {
    memset(a->prev, 0, sizeof(a->prev));
    memset(a->X, 0, sizeof(a->X));
    a->mp = a->pp = a->share = 0; // how well it predicted says nothing about the new stream
    a->partial = P;
    // It stays as sure of the path as it was: made unsure, it lurches away from a right one for
    // the first second of every stream. A path that did move trips the guard, which unsettles it.
}

void aec_shift(aec* a, int k) {
    if (k >= P || k <= -P) {
        aec_reset(a);
        return;
    }
    if (k > 0) {
        memmove(a->W[0], a->W[k], (P - k) * sizeof(a->W[0]));
        memmove(a->G[0], a->G[k], (P - k) * sizeof(a->G[0]));
        memmove(a->Pc[0], a->Pc[k], (P - k) * sizeof(a->Pc[0]));
    } else if (k < 0) {
        memmove(a->W[-k], a->W[0], (P + k) * sizeof(a->W[0]));
        memmove(a->G[-k], a->G[0], (P + k) * sizeof(a->G[0]));
        memmove(a->Pc[-k], a->Pc[0], (P + k) * sizeof(a->Pc[0]));
    }
    int from = k > 0 ? P - k : 0, to = k > 0 ? P : -k;
    for (int p = from; p < to; p++) {
        memset(a->W[p], 0, sizeof(a->W[p]));
        memset(a->G[p], 0, sizeof(a->G[p]));
        for (int i = 0; i < K; i++)
            a->Pc[p][i] = P_INIT;
    }
    a->rr = 0;
    aec_forget(a);
}

void aec_refill(aec* a, const float* hist) {
    memset(a->prev, 0, sizeof(a->prev));
    a->partial = 0;
    for (int p = 0; p < P; p++) {
        bool silent = true;
        for (int i = 0; i < N && silent; i++)
            silent = hist[p * N + i] == 0;
        if (silent)
            a->partial = p + 1; // nothing played then, or nothing captured: maybe missing
        memcpy(a->tbuf, a->prev, N * sizeof(float));
        memcpy(a->tbuf + N, hist + p * N, N * sizeof(float));
        memcpy(a->prev, hist + p * N, N * sizeof(float));
        fftwf_execute(a->fwd);
        a->head = (a->head + 1) % P;
        memcpy(a->X[a->head], a->fbuf, K * sizeof(float complex));
    }
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
    if (a->partial > 0)
        a->partial--;

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
        pred[i] = a->pred[i] = a->tbuf[N + i] * scale;
}

bool aec_update(aec* a, const float* err, bool learn) {
    // Subtraction must not make the mic louder: a filter that does is out of date (the phone moved)
    // or blown up. One that blew up starts over.
    float pm = 0, po = 0, mp = 0, pp = 0;
    for (int i = 0; i < N; i++) {
        float m = err[i] + a->pred[i];
        pm += m * m;
        po += err[i] * err[i];
        mp += m * a->pred[i];
        pp += a->pred[i] * a->pred[i];
    }
    if (!isfinite(po)) {
        aec_reset(a);
        return true;
    }
    if (pm == 0)
        return false; // the phone sent exact silence (its mic starting, or muted): nothing to judge or learn
    // The share of the prediction that leaves the least behind, over the last ~90 ms: 1 when the
    // filter is right (what else the mic holds is unrelated to it), less when its echo is louder
    // than the real one or unlike it, 0 when it is unrelated, more when the real echo is louder
    // (the path changed, or the filter is still learning it). Subtracting that share
    // keeps the output from being louder than the mic, whatever the filter believes, while the
    // filter itself still learns from all of it.
    a->mp = SHARE_SMOOTH * a->mp + mp;
    a->pp = SHARE_SMOOTH * a->pp + pp;
    // Not past 1 while the speaker history has holes: the prediction then lacks the lags it
    // doesn't have, and a share fitted to that remainder blows up once the history fills in.
    a->share = a->pp > 0 && a->mp > 0 ? fminf(a->mp / a->pp, a->partial ? 1 : SHARE_MAX) : 0;
    if (!learn)
        return false; // holding still (being aligned, or the speakers silent): judge it later
    a->mic_pow = GUARD_SMOOTH * a->mic_pow + pm;
    a->out_pow = GUARD_SMOOTH * a->out_pow + po;
    // The prediction is well off the echo the mic holds: louder, or (and plainly the echo's shape,
    // correlation over 0.5) less than half of it.
    bool louder = a->out_pow > 2 * a->mic_pow;
    bool quieter = a->share > 2 && a->mp * a->mp > 0.25f * a->pp * a->mic_pow;
    if (++a->guard_n >= GUARD_MIN_BLOCKS && (louder || quieter) && a->mic_pow > N * 1e-9f) {
        // Wiping it would leave the loop through the speakers uncancelled while it relearns, and
        // in a closed loop relearning itself overshoots at first. Scale it by the share that
        // matches and make it quick to adapt: right at once after a volume change, and from a path
        // that is all wrong nearly a fresh start, which learns faster than unlearning (its errors
        // count as noise and slow it).
        float s = a->share;
        for (int p = 0; p < P; p++)
            for (int k = 0; k < K; k++) {
                a->W[p][k] *= s;
                if (a->Pc[p][k] < P_INIT)
                    a->Pc[p][k] = P_INIT;
            }
        a->mp *= s; // as if it had predicted that much all along: all of it matches now
        a->pp *= s * s;
        a->share = s > 0 ? 1 : 0;
        a->mic_pow = a->out_pow = 0;
        a->guard_n = 0;
        return true;
    }
    if (a->guard_n >= GOOD_BLOCKS && a->mic_pow > 4 * a->out_pow && ++a->good_n >= GOOD_BLOCKS) {
        memcpy(a->G, a->W, sizeof(a->W));
        a->have_good = true;
        a->good_n = 0;
    }
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
    return false;
}

int aec_align(aec* a, const float* pred, const float* mic) {
    const int n = AEC_ALIGN, m = 2 * n;
    memset(a->at, 0, m * sizeof(float));
    memcpy(a->at, pred, n * sizeof(float));
    fftwf_execute(a->apf);
    memset(a->at, 0, m * sizeof(float));
    memcpy(a->at, mic, n * sizeof(float));
    fftwf_execute(a->bpf);
    // Phase transform over 200 Hz - 8 kHz, as in aec_find_delay: one sharp peak at the shift.
    for (int k = 0; k <= n; k++) {
        float f = (float)k * 48000 / m;
        float complex g = ((float complex*)a->bf)[k] * conjf(((float complex*)a->af)[k]);
        float mag = cabsf(g);
        ((float complex*)a->af)[k] = (f < 200 || f > 8000 || mag < 1e-20f) ? 0 : g / mag;
    }
    fftwf_execute(a->api);
    int best = 0;
    double sum = 0;
    for (int s = -AEC_ALIGN_MAX; s <= AEC_ALIGN_MAX; s++) {
        float v = a->at[s < 0 ? s + m : s];
        sum += fabsf(v);
        if (v > a->at[best < 0 ? best + m : best])
            best = s;
    }
    float y0 = a->at[best < 0 ? best + m : best];
    float q = y0 / (float)(sum / (2 * AEC_ALIGN_MAX + 1) + 1e-30);
    if (q <= 8)
        return AEC_NO_SHIFT;
    // The rest of the move is a fraction of a sample, which at 7 kHz is still most of the way to
    // cancelling nothing (one sample there is 53 degrees): find it between the samples round the
    // peak and delay the filter by it, a phase ramp across each partition's spectrum.
    float ym = a->at[best - 1 < 0 ? best - 1 + m : best - 1], yp = a->at[best + 1 < 0 ? best + 1 + m : best + 1];
    float den = ym - 2 * y0 + yp;
    float frac = den < 0 ? 0.5f * (ym - yp) / den : 0;
    frac = fmaxf(-0.5f, fminf(0.5f, frac));
    for (int k = 0; k < K; k++) {
        float complex r = cexpf(-I * (float)M_PI * k * frac / N);
        for (int p = 0; p < P; p++)
            a->W[p][k] *= r;
    }
    // How well it matched before the move says nothing about after: judge it afresh.
    a->mp = a->pp = a->share = 0;
    a->mic_pow = a->out_pow = 0;
    a->guard_n = 0;
    return best;
}

void aec_get_path(const aec* a, float* path) { memcpy(path, a->W, sizeof(a->W)); }

void aec_set_path(aec* a, const float* path) {
    memcpy(a->W, path, sizeof(a->W));
    memcpy(a->G, path, sizeof(a->G)); // it was kept because it cancelled well
    a->have_good = true;
    aec_forget(a);
}

void aec_recall(aec* a) {
    if (a->have_good)
        memcpy(a->W, a->G, sizeof(a->W));
}

float aec_share(const aec* a) { return a->share; }

bool aec_knows_path(const aec* a) {
    for (int p = 0; p < P; p++)
        for (int k = 0; k < K; k++)
            if (crealf(a->W[p][k]) != 0 || cimagf(a->W[p][k]) != 0)
                return true;
    return false;
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
    // The direct sound comes first; a reflection or the farther speaker can still peak higher.
    // Take the earliest peak within 6 dB of the highest and at most 15 ms before it.
    for (int i = best > 720 ? best - 720 : 1; i < best; i++) {
        if (t[i] >= 0.5f * t[best] && t[i] >= t[i - 1] && t[i] >= t[i + 1]) {
            best = i;
            break;
        }
    }
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
