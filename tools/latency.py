# Per-click delay between speaker output (left) and the phone mic (right) in one capture.
import sys, numpy as np
d=open(sys.argv[1],'rb').read(); i=d.find(b'data'); x=np.frombuffer(d[i+8:],dtype='<f4').reshape(-1,2).astype(np.float64)
out,mic=x[:,0],x[:,1]
print(f"out peak {np.max(np.abs(out)):.3f}  mic peak {np.max(np.abs(mic)):.4f}")
def onsets(s):
    s=s-np.convolve(s,np.ones(32)/32,'same')  # drop rumble
    e=np.convolve(np.abs(s),np.ones(96)/96,'same'); th=0.35*e.max()
    idx=np.where((e[1:]>th)&(e[:-1]<=th))[0]; keep=[]
    for j in idx:
        if not keep or j-keep[-1]>9600: keep.append(j)
    return np.array(keep)
oa,ob=onsets(out),onsets(mic)
lags=[(m-o)/48 for o in oa for m in ob if 0<m-o<500*48]
print("per-click lag ms:", [round(l,1) for l in lags])
if lags: print(f"median {np.median(lags):.1f} ms")
