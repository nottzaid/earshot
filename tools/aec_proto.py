#!/usr/bin/env python3
"""Offline prototype of Earshot's echo canceller (streaming, causal; the C plugin mirrors it).

Output is always  mic - prediction,  where the prediction is the speaker signal through an
adaptive filter. Nothing else touches the mic signal.

Why it's more than a textbook canceller: the phone's audio reaches the PC through a jitter buffer
whose resampler keeps nudging the delay (smooth ramps of a few samples per second, the odd jump).
A linear filter can't follow that, so a timing loop keeps the *speaker reference* aligned instead:
with the filter converged, a delay error d leaves a residual of about -d * (derivative of the
prediction), so correlating the two measures d every block. The reference is shifted by a
windowed-sinc fractional delay; the mic is never resampled.

  aec_proto.py capture.wav [--near voice.wav --near-db -30] [--save out.f32]
capture.wav: 3 channels (speaker L, speaker R, mic) from tools/aec-capture.sh.
"""
import argparse
import numpy as np

FS = 48000

def read3(path):
    d = open(path, "rb").read(); i = d.find(b"data"); b = d[i + 8:]
    return np.frombuffer(b[:len(b) // 12 * 12], dtype="<f4").reshape(-1, 3).astype(np.float64)

def read1(path):
    d = open(path, "rb").read(); i = d.find(b"data"); b = d[i + 8:]
    return np.frombuffer(b[:len(b) // 4 * 4], dtype="<f4").astype(np.float64)


class Aligner:
    """Delays the reference by a fractional, slowly varying number of samples."""
    TAPS = 32

    def __init__(self, history):
        self.buf = np.zeros(history)
        self.win = np.blackman(self.TAPS)

    def push(self, x):
        self.buf = np.concatenate([self.buf[len(x):], x])

    def read(self, n, delay):
        """The last n samples, delayed by `delay` samples."""
        half = self.TAPS // 2
        i = int(np.floor(delay)); fr = delay - i
        h = np.sinc(np.arange(-half + 1, half + 1) - fr) * self.win; h /= h.sum()
        end = len(self.buf) - i
        seg = self.buf[end - n - half: end + half - 1]
        return np.convolve(seg, h[::-1], "valid")[:n]


def gcc_delay(ref, mic, lo=200, hi=6000):
    n = 1 << int(np.ceil(np.log2(2 * len(ref))))
    A = np.fft.rfft(ref, n); B = np.fft.rfft(mic, n)
    f = np.fft.rfftfreq(n, 1 / FS); G = B * np.conj(A); G[(f < lo) | (f > hi)] = 0
    G /= np.abs(G) + 1e-12
    c = np.fft.irfft(G, n)[:FS // 4]; k = int(np.argmax(c))
    return k, c[k]


class Canceller:
    LOOP_BLOCKS = 16                                  # 43 ms per loop update
    KP, KI = 0.11, 0.0064                             # PI gains: ~0.3 Hz, damping 0.7
    def __init__(self, N=128, taps_ms=150, margin_ms=5, mu=0.6, loop=True):
        self.N, self.P = N, -(-int(taps_ms * FS / 1000) // N)
        K = N + 1
        self.X = np.zeros((self.P, K), complex)
        self.Wf = np.zeros_like(self.X); self.Wb = np.zeros_like(self.X)
        self.prev = np.zeros(N)
        self.pow = np.zeros(K)
        self.mu = mu; self.loop = loop
        self.sf = self.sb = 0.0; self.better = 0; self.rr = 0
        self.copies = self.resets = 0
        self.margin = margin_ms * FS / 1000
        self.align = Aligner(FS)                      # 1 s of reference history
        self.delay = None                             # reference delay in samples (None: acquiring)
        self.vel = 0.0                                # samples per loop update
        self.num = self.den = 0.0; self.nblk = 0
        self.acq_ref = []; self.acq_mic = []
        self.erle = 0.0
        self.track = []

    def _acquire(self, ref, mic):
        self.acq_ref.append(ref); self.acq_mic.append(mic)
        r = np.concatenate(self.acq_ref); m = np.concatenate(self.acq_mic)
        if len(r) >= 2 * FS:
            if np.mean(r ** 2) > 1e-7:
                k, q = gcc_delay(r, m)
                if q > 0.05:
                    self.delay = k - self.margin
                    self.acq_ref = []; self.acq_mic = []
                    return
            self.acq_ref = [r[-FS:]]; self.acq_mic = [m[-FS:]]

    def block(self, ref_in, mic):
        N, P = self.N, self.P
        self.align.push(ref_in)
        if self.delay is None:
            self._acquire(ref_in, mic)
            self.track.append(None)
            return mic.copy()
        ref = self.align.read(N, self.delay)

        frame = np.concatenate([self.prev, ref]); self.prev = ref
        self.X = np.roll(self.X, 1, axis=0); self.X[0] = np.fft.rfft(frame)
        Yf = (self.Wf * self.X).sum(0)
        yf = np.fft.irfft(Yf)[N:]
        yb = np.fft.irfft((self.Wb * self.X).sum(0))[N:]
        ef = mic - yf; eb = mic - yb

        # background adaptation, normalized per bin by the reference energy across the whole filter
        # span (not just the newest block: a loud passage still inside the filter would otherwise
        # make the step far too large), regularized so near-empty bins don't learn the mic's noise
        # (floor: a reference at -60 dBFS; no learning at all while the speakers are silent)
        active = ref @ ref > N * 1e-7
        if active:
            self.pow = (np.abs(self.X) ** 2).sum(0)
            delta = 1e-3 * self.pow.mean() + P * (2 * N) ** 2 * 1e-6
            E = np.fft.rfft(np.concatenate([np.zeros(N), eb]))
            self.Wb += np.conj(self.X) * (self.mu * E / (self.pow + delta))
            p = self.rr; self.rr = (self.rr + 1) % P
            w = np.fft.irfft(self.Wb[p]); w[N:] = 0; self.Wb[p] = np.fft.rfft(w)

        # two-path control
        a = 0.25
        ef2, eb2, m2 = ef @ ef, eb @ eb, mic @ mic
        self.sf = (1 - a) * self.sf + a * ef2; self.sb = (1 - a) * self.sb + a * eb2
        if active and self.sb < 0.7 * self.sf:
            self.better += 1
            if self.better >= 3:
                self.Wf[:] = self.Wb; self.sf = self.sb; self.better = 0; self.copies += 1
        else:
            self.better = 0
        if self.sb > 4 * self.sf + 1e-12:
            self.Wb[:] = self.Wf; self.sb = self.sf; self.resets += 1

        # timing loop: residual ~ -d * dy/dt when the echo is d samples later than predicted.
        # Correlations are summed over LOOP_BLOCKS blocks, then a 2nd-order (PI) loop with ~0.3 Hz
        # bandwidth moves the reference: quiet on plateaus, follows ramps without lag.
        if active:
            self.erle = 0.95 * self.erle + 0.05 * (10 * np.log10((m2 + 1e-20) / (ef2 + 1e-20)))
            if self.loop and self.erle > 3:
                k = np.arange(N + 1)
                dy = np.fft.irfft(Yf * (1j * np.pi * k / N))[N:]
                self.num += ef @ dy; self.den += dy @ dy
        self.nblk += 1
        if self.nblk == self.LOOP_BLOCKS:
            if self.den > 1e-12:
                d = float(np.clip(-self.num / self.den, -1, 1))
                self.vel += self.KI * d
                self.delay += self.KP * d
            self.delay += self.vel
            self.nblk = 0; self.num = self.den = 0.0
        self.track.append(self.delay)
        return ef


class KalmanCanceller:
    """Partitioned-block frequency-domain Kalman filter (diagonalized; Enzner & Vary, Kuech et al.).

    Per bin and partition it tracks the state uncertainty P; the near-end/noise power Phi is
    estimated from the error. The step  mu = P / (sum |X|^2 P + Phi)  is large when the echo path
    is uncertain (it moved) and small when the mic holds something other than echo (a voice, a
    TV), so it never learns those. Output: mic - prediction, nothing else.
    """

    def __init__(self, N=128, taps_ms=150, margin_ms=5, A=0.9998, beta=0.5, **_):
        self.N, self.P = N, -(-int(taps_ms * FS / 1000) // N)
        K = N + 1
        self.X = np.zeros((self.P, K), complex); self.W = np.zeros_like(self.X)
        self.Pc = np.ones((self.P, K)) * 1e-4      # state error variance per partition/bin
        self.phi = np.zeros(K)
        self.A2, self.beta = A * A, beta
        self.prev = np.zeros(N); self.rr = 0
        self.margin = margin_ms * FS / 1000
        self.align = Aligner(FS); self.delay = None
        self.acq_ref = []; self.acq_mic = []; self.track = []
        self.copies = self.resets = 0

    _acquire = Canceller._acquire

    def block(self, ref_in, mic):
        N = self.N
        self.align.push(ref_in)
        if self.delay is None:
            self._acquire(ref_in, mic); self.track.append(None)
            return mic.copy()
        ref = self.align.read(N, self.delay)
        frame = np.concatenate([self.prev, ref]); self.prev = ref
        self.X = np.roll(self.X, 1, axis=0); self.Pc = np.roll(self.Pc, 1, axis=0)
        self.X[0] = np.fft.rfft(frame)
        y = np.fft.irfft((self.W * self.X).sum(0))[N:]
        e = mic - y
        E = np.fft.rfft(np.concatenate([np.zeros(N), e]))
        X2 = np.abs(self.X) ** 2
        self.phi = self.beta * self.phi + (1 - self.beta) * np.abs(E) ** 2
        R = (X2 * self.Pc).sum(0) * 0.5 + self.phi   # 0.5: N of the 2N frame carry the error
        mu = self.Pc / (R + 1e-20)
        self.W += mu * np.conj(self.X) * E
        p = self.rr; self.rr = (self.rr + 1) % self.P
        w = np.fft.irfft(self.W[p]); w[N:] = 0; self.W[p] = np.fft.rfft(w)
        self.Pc = self.A2 * (1 - 0.5 * mu * X2) * self.Pc + (1 - self.A2) * np.abs(self.W) ** 2
        self.track.append(self.delay)
        return e


def run(x, near=None, **kw):
    ref = x[:, :2].mean(1); mic = x[:, 2].copy()
    if near is not None: mic = mic + near
    kw = dict(kw); kind = kw.pop("kind", Canceller)
    c = kind(**kw); N = c.N
    n = len(mic) // N * N; out = np.empty(n)
    for k in range(n // N):
        s = slice(k * N, (k + 1) * N)
        out[s] = c.block(ref[s], mic[s])
    return out, mic[:n], c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav"); ap.add_argument("--near"); ap.add_argument("--near-db", type=float, default=-30)
    ap.add_argument("--save"); ap.add_argument("--taps-ms", type=float, default=150)
    ap.add_argument("--mu", type=float, default=0.6); ap.add_argument("--no-loop", action="store_true")
    ap.add_argument("--kalman", action="store_true")
    a = ap.parse_args()
    x = read3(a.wav)
    kw = dict(taps_ms=a.taps_ms, mu=a.mu, loop=not a.no_loop)
    if a.kalman: kw["kind"] = KalmanCanceller
    out, mic, c = run(x, **kw)
    db = lambda v: 10 * np.log10(np.mean(v ** 2) + 1e-20)
    print(f"copies {c.copies} resets {c.resets}")
    for s in range(0, int(len(out) / FS) - 9, 10):
        sl = slice(s * FS, (s + 10) * FS)
        dl = c.track[min(s * FS // c.N, len(c.track) - 1)]
        print(f"{s:3d}-{s+10:3d}s  mic {db(mic[sl]):6.1f}  out {db(out[sl]):6.1f}  ERLE {db(mic[sl])-db(out[sl]):5.1f} dB"
              f"  ref delay {dl/48 if dl is not None else float('nan'):7.3f} ms")
    if a.near:
        v = read1(a.near); v = np.pad(v, (0, max(0, len(x) - len(v))))[:len(x)]
        v *= 10 ** (a.near_db / 20) / (np.sqrt(np.mean(v[v != 0] ** 2)) + 1e-12)
        out2, _, c2 = run(x, near=v, **kw)
        diff = out2 - out[:len(out2)] - v[:len(out2)]
        print(f"near-end voice at {a.near_db} dBFS: the canceller changed it by "
              f"{db(diff) - db(v[:len(out2)]):.1f} dB relative (copies {c2.copies}, resets {c2.resets})")
    if a.save:
        np.stack([mic, out], 1).astype("<f4").tofile(a.save)

if __name__ == "__main__":
    main()
