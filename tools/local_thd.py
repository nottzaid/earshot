# Per-20ms THD+N of a recorded test tone (robust to slow pitch drift from clock correction).
import sys, numpy as np
d=open(sys.argv[1],'rb').read(); i=d.find(b'data'); x=np.frombuffer(d[i+8:],dtype='<f4').astype(np.float64); sr=48000
act=np.abs(x)>1e-4; s=np.argmax(act); x=x[s+2400:len(x)-np.argmax(act[::-1])-2400]
W=960; t=np.arange(W)/sr; out=[]
fs=np.linspace(438,442,161)
basis=[(np.column_stack([np.sin(2*np.pi*f*t),np.cos(2*np.pi*f*t)])) for f in fs]
for k in range(0,len(x)-W,W):
    w=x[k:k+W]; best=None
    for A in basis:
        c,*_=np.linalg.lstsq(A,w,rcond=None); e=np.sqrt(np.mean((w-A@c)**2))
        if best is None or e<best[0]: best=(e,np.hypot(*c))
    out.append(20*np.log10(max(best[0],1e-9)/(best[1]/np.sqrt(2))))
out=np.array(out)
print(f"{len(out)} windows: median {np.median(out):.0f} dB, worst {out.max():.0f} dB, bad(>-60) {(out>-60).sum()}")
