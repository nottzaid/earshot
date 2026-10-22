# Checks a recording of the test tone: level, pitch, and gaps.
import sys, wave, numpy as np
import struct
with open(sys.argv[1], 'rb') as f:
    data = f.read()
# pw-record writes a WAV; find the 'data' chunk
i = data.find(b'data')
pcm = np.frombuffer(data[i+8:], dtype='<f4')
sr = 48000
print(f"samples={len(pcm)} ({len(pcm)/sr:.2f}s) peak={np.max(np.abs(pcm)):.3f} rms={np.sqrt(np.mean(pcm**2)):.3f}")
active = np.abs(pcm) > 1e-4
if active.any():
    s, e = np.argmax(active), len(active) - np.argmax(active[::-1])
    seg = pcm[s:e]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    f = np.fft.rfftfreq(len(seg), 1/sr)[np.argmax(spec)]
    # gaps: runs of >=1ms near-silence inside the tone
    quiet = np.abs(seg) < 0.02
    runs, cur = [], 0
    for q in quiet:
        if q: cur += 1
        else:
            if cur > 48: runs.append(cur)
            cur = 0
    # residual vs ideal sine at detected freq (THD+N proxy)
    print(f"tone {f:.1f} Hz over {len(seg)/sr:.2f}s, gaps>1ms: {len(runs)}")
    # THD+N: fit a sine at the measured pitch over the steady middle, report residual level
    mid = seg[len(seg)//4: 3*len(seg)//4]
    t = np.arange(len(mid)) / sr
    best = None
    for ff in np.linspace(f - 1, f + 1, 201):
        A = np.column_stack([np.sin(2*np.pi*ff*t), np.cos(2*np.pi*ff*t), np.ones_like(t)])
        coef, *_ = np.linalg.lstsq(A, mid, rcond=None)
        r = mid - A @ coef
        e = np.sqrt(np.mean(r**2))
        if best is None or e < best[0]: best = (e, ff, np.hypot(coef[0], coef[1]))
    e, ff, amp = best
    print(f"THD+N {20*np.log10(e/(amp/np.sqrt(2))):.1f} dB, amplitude {amp:.4f}")
