# Counts exact-zero runs (ROC fills lost/late audio with silence) in a mic recording.
import sys, numpy as np
d=open(sys.argv[1],'rb').read(); i=d.find(b'data'); x=np.frombuffer(d[i+8:],dtype='<f4')
x=x[np.argmax(np.abs(x)>0):]
z=(x==0).astype(np.int8); dz=np.diff(np.concatenate([[0],z,[0]])); s=np.where(dz==1)[0]; L=np.where(dz==-1)[0]-s
bad=[(round(a/48000,2),round(b/48,1)) for a,b in zip(s,L) if b>=24]
print(f"{len(x)/48000:.1f}s, rms {20*np.log10(np.sqrt(np.mean(x**2))+1e-12):.1f} dBFS, dropouts>=0.5ms: {len(bad)} {bad[:12]}")
