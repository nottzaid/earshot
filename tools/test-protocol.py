# Drives earshotd's control protocol (v2) as a fake phone. Run against a daemon on localhost.
import hashlib, hmac, json, secrets, socket, subprocess, sys, time

V = 2
def proof(secret, role, a, b):
    return hmac.new(bytes.fromhex(secret), f"earshot/{V}/{role}/{a}/{b}".encode(), hashlib.sha256).hexdigest()

class Phone:
    def __init__(self, dev="phone-A"):
        self.s = socket.create_connection(("127.0.0.1", 10004), timeout=5); self.f = self.s.makefile("rw"); self.dev = dev
    def send(self, m): self.f.write(json.dumps(m) + "\n"); self.f.flush()
    def recv(self):
        line = self.f.readline()
        return json.loads(line) if line else None
    def until(self, pred, n=20):
        for _ in range(n):
            m = self.recv()
            if m is None or pred(m): return m
    def hello(self, pair, **kw):
        self.nonce = secrets.token_hex(16)
        self.send({"t": "hello", "v": V, "id": self.dev, "name": "Fake", "nonce": self.nonce, "pair": pair, **kw})

def check(name, ok):
    print(("PASS " if ok else "FAIL ") + name); globals().setdefault("fails", 0)
    if not ok: globals()["fails"] += 1

u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(2)
u.sendto(b"EARSHOT?2", ("127.0.0.1", 10005)); here = json.loads(u.recvfrom(2000)[0])
check("discovery advertises pc id and pairable", here.get("pc") and here.get("pairable") is True and "paired" not in here)
pc_id = here["pc"]

a = Phone(); a.hello(pair=False); m = a.recv()
check("unpaired phone without pair request is refused", m == {"t": "denied", "reason": "not-paired"})

a = Phone(); a.hello(pair=True); m = a.recv()
check("explicit pairing returns pc id and secret", m["t"] == "paired" and m["pc"] == pc_id and len(m["secret"]) == 64)
secret = m["secret"]
st = a.until(lambda x: x.get("t") == "state")
check("state follows pairing", st is not None and "mute_seq" in st)
a.s.close()

b = Phone("phone-B"); b.hello(pair=True); m = b.recv()
check("second phone cannot pair over the first", m == {"t": "denied", "reason": "paired-elsewhere"})

u.sendto(b"EARSHOT?2", ("127.0.0.1", 10005)); check("discovery no longer pairable", json.loads(u.recvfrom(2000)[0])["pairable"] is False)

a = Phone(); a.hello(pair=False); ch = a.recv()
check("challenge carries a valid PC proof", ch["t"] == "challenge" and hmac.compare_digest(ch["proof"], proof(secret, "pc", a.nonce, ch["nonce"])))
a.send({"t": "auth", "proof": "00" * 32}); check("wrong phone proof is refused", a.recv() == {"t": "denied", "reason": "bad-proof"})

a = Phone(); a.hello(pair=False); ch = a.recv()
a.send({"t": "auth", "proof": proof(secret, "phone", ch["nonce"], a.nonce)}); w = a.recv()
check("right proof gets welcome", w["t"] == "welcome")
a.send({"t": "set", "muted": True, "seq": 5})
st = a.until(lambda x: x.get("t") == "state" and x.get("mute_seq") == 5)
check("mute applied and echoed with its seq", st and st["muted"] is True)
time.sleep(0.5)
out = subprocess.run(["pactl", "get-source-mute", "earshot.mic"], capture_output=True, text=True).stdout
check("PipeWire node really muted", "yes" in out)
# receiver restart (mode change) must keep the mute
a.send({"t": "set", "mode": "tight"}); time.sleep(3)
out = subprocess.run(["pactl", "get-source-mute", "earshot.mic"], capture_output=True, text=True).stdout
check("mute survives a receiver restart", "yes" in out)
# PC-side change is reported with the current seq
subprocess.run(["pactl", "set-source-mute", "earshot.mic", "0"])
st = a.until(lambda x: x.get("t") == "state" and x.get("muted") is False)
check("PC-side unmute reported with current seq", st and st["mute_seq"] == 5)
a.send({"t": "set", "mode": "balanced"}); time.sleep(2.5)
a.send({"t": "unpair"}); check("phone can unpair itself", a.until(lambda x: x.get("t") == "unpaired") is not None)
u.sendto(b"EARSHOT?2", ("127.0.0.1", 10005)); check("pairable again after unpair", json.loads(u.recvfrom(2000)[0])["pairable"] is True)
print("FAILURES:", globals().get("fails", 0)); sys.exit(1 if globals().get("fails") else 0)
