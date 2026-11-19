#!/usr/bin/env python3
"""Mock robot Omni-3X untuk menguji app tanpa robot. Hanya library standar Python.

  python3 tools/mock_ws.py [--port 81] [--bad 25] [--sf-after 10]

Server WebSocket ws://0.0.0.0:<port>/ws: memancarkan telemetri lengkap (§5.2 PRD) tiap 100 ms,
mencetak setiap perintah yang diterima (kecuali hb; tambah --hb), dan menggerakkan state secukupnya
agar kartu app hidup. Failsafe 300 ms ditiru: tanpa hb, jog/otonom berhenti.
  --bad N       sisipkan pesan rusak + field asing tiap N telemetri (uji ketahanan parser)
  --sf-after S  picu alarm pengaman (sf=1) S detik setelah klien tersambung
"""
import argparse, asyncio, base64, hashlib, json, math, random, struct, time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=81)
ap.add_argument("--bad", type=int, default=0)
ap.add_argument("--sf-after", type=float, default=0)
ap.add_argument("--hb", action="store_true")
A = ap.parse_args()

clients = set()
S = dict(x=0.0, y=0.0, yaw=0.0, mode=2, tgt=0, jg=0, jog=0, so=True, sf=0, pl=False, pr=False, np=0, nd=0, rty=0,
         cs=[150, 300, 200, 150], ps=[0, 0, 0, 0], tune=0, tw=0, svseq=0, sv=[0, 0, 0], dn=[0, 0], dnseq=0,
         kp=[1.0, 1.0, 1.0], ki=[0.5, 0.5, 0.5], kd=[0.0, 0.0, 0.0], rts=[[1, 2, 3], [2], [3], [4]],
         wp=[[1, 100, 0, 90, 1, 40, 40, 0], [1, 100, 100, 180, 1, 0, 0, 0], [1, -50, 100, 0, 0, 0, 0, 0], [1, 0, 0, 0, 1, 0, 0, 0]] +
            [[0, 0, 0, 0, 0, 0, 0, 0] for _ in range(12)],
         mc=dict(s=0, dir=0, why=0, o=0, i=0, yi=True, dx=0, dy=0, ix=0, iy=0, dyaw=0),
         last_hb=0.0, t0=time.time(), until=0.0, mtq=0.0, tune_until=0.0, connected_at=0.0, sf_done=False)
SP = [0.0, 0.0, 0.0]


def deny(code):
    S["dn"] = [code, S["dnseq"] + 1]; S["dnseq"] += 1


def saved(what):
    S["svseq"] += 1; S["sv"] = [1, what, S["svseq"]]


def cmd(c):
    k = c.get("cmd")
    i = int(c.get("id", 0)) if isinstance(c.get("id", 0), (int, float)) else 0
    if k == "stop" or k == "stop_save":
        S.update(mode=2, jog=0, jg=0, tgt=0, ps=[0, 0, 0, 0], np=0)
        if k == "stop_save":
            if S["pl"]: deny(1)
            elif 1 <= i <= 16: S["wp"][i - 1] = [1, round(S["x"], 1), round(S["y"], 1), round(S["yaw"], 1), 1, 0, 0, 0]; saved(1)
    elif k == "jog":
        d = int(c.get("d", 0))
        if S["sf"]: deny(3)
        elif d: S.update(mode=4, jog=d, jg=d)
        else: S.update(mode=2, jog=0, jg=0)
    elif k == "speed":
        for n, key in enumerate(["man", "turbo", "nav", "loc"]):
            if key in c: S["cs"][n] = max(30, min(500, int(c[key])))
        saved(5)
    elif k == "odom_reset": S.update(x=0.0, y=0.0, yaw=0.0, pl=False, pr=False); saved(3)
    elif k in ("goto", "preset", "route_go"):
        if S["pl"]: deny(1)
        elif S["sf"]: deny(3)
        elif S["mode"] in (1, 3): deny(4)
        else:
            if k == "preset": r = int(c.get("i", -1))
            elif k == "route_go": r = int(c.get("r", -1))
            else: r = -1
            if k != "goto" and not (0 <= r < 4 and S["rts"][r]): deny(2); return
            if k == "goto" and not (1 <= i <= 16 and S["wp"][i - 1][0]): deny(2); return
            S["mode"] = 1; S["tgt"] = i if k == "goto" else S["rts"][r][0]; S["until"] = time.time() + 3
            if k != "goto": S["ps"][r] = 2
    elif k == "route_set":
        r = int(c.get("r", -1))
        if 0 <= r < 4: S["rts"][r] = [int(v) for v in c.get("ids", []) if 1 <= int(v) <= 16 or int(v) in (101, 102)][:16]; saved(4)
    elif k == "grip":
        S["gp"] = bool(c.get("on", False))
    elif k == "wp_here":
        if S["pl"]: deny(1)
        elif 1 <= i <= 16: S["wp"][i - 1] = [1, round(S["x"], 1), round(S["y"], 1), round(S["yaw"], 1), 1, 30, 30, 0]; saved(1)
    elif k == "wp_set" and 1 <= i <= 16:
        old = S["wp"][i - 1]
        has = isinstance(c.get("yaw"), (int, float))
        S["wp"][i - 1] = [1, c.get("x", 0), c.get("y", 0), c.get("yaw", 0) if has else (old[3] if not c.get("noyaw") else 0),
                          1 if has or (old[4] and not c.get("noyaw")) else 0, c.get("wallL", 0), c.get("wallR", 0), c.get("wallF", 0)]; saved(1)
    elif k == "wp_sync":
        if not (1 <= i <= 16 and S["wp"][i - 1][0] and any(S["wp"][i - 1][5:8])): deny(8)
    elif k == "wp_del" and 1 <= i <= 16: S["wp"][i - 1] = [0] * 8; saved(1)
    elif k == "safety": S["so"] = bool(c.get("on", True)); saved(5)
    elif k == "resume": S["sf"] = 0
    elif k == "mtest":
        if S["mode"] == 1: deny(4)
        else: S["mc"].update(s=1, dir=int(c.get("dir", 1)), why=0); S["mtq"] = time.time() + 1.5; S["mode"] = 3
    elif k == "pid":
        m = int(c.get("m", -1))
        for w in ([m] if m >= 0 else [0, 1, 2]):
            if 0 <= w < 3: S["kp"][w], S["ki"][w], S["kd"][w] = c.get("kp", 0), c.get("ki", 0), c.get("kd", 0)
        saved(2)
    elif k == "autotune": S["tune"] = 1; S["tune_until"] = time.time() + 2.5
    # ramp_mark dan hb: tidak ada efek


def step(dt):
    now = time.time()
    if S["jog"] and now - S["last_hb"] > 0.3:
        print("FAILSAFE: tanpa hb > 300 ms -> robot berhenti"); S.update(mode=2, jog=0, jg=0, ps=[0, 0, 0, 0])
    if S["mode"] == 4:
        v = 20 * dt
        dx = {1: 0, 2: 0, 3: -v, 4: v}.get(S["jog"], 0); dy = {1: v, 2: -v}.get(S["jog"], 0)
        a = math.radians(S["yaw"]); S["x"] += dx * math.cos(a) + dy * math.sin(a); S["y"] += -dx * math.sin(a) + dy * math.cos(a)
        if S["jog"] == 5: S["yaw"] -= 40 * dt
        if S["jog"] == 6: S["yaw"] += 40 * dt
    if S["mode"] == 1:
        w = S["wp"][S["tgt"] - 1] if 1 <= S["tgt"] <= 16 else None
        if w and w[0]:
            S["x"] += (w[1] - S["x"]) * min(1, 2 * dt); S["y"] += (w[2] - S["y"]) * min(1, 2 * dt)
        if now > S["until"]: S["mode"] = 2; S["tgt"] = 0; S["ps"] = [0, 0, 0, 0]; S["nd"] += 1; S["np"] = 0
        elif S["until"] - now < 1: S["np"] = 2
        else: S["np"] = 1
    if S["mc"]["s"] == 1 and now > S["mtq"]:
        S["mc"].update(s=2, o=1, i=1, dx=1.2, dy=48.5, ix=2.0, iy=47.0, dyaw=2.5); S["mode"] = 2
    if S["tune"] == 1 and now > S["tune_until"]: S["tune"] = 2
    if A.sf_after and S["connected_at"] and not S["sf_done"] and now - S["connected_at"] > A.sf_after:
        S["sf"] = 2; S["sf_done"] = True; S.update(mode=2, jog=0, jg=0)


def telemetry():
    t = time.time() - S["t0"]
    moving = S["mode"] in (1, 4)
    sp = [round((120 if moving else 0) * (1 if k != 2 else -1) * (0.9 + 0.1 * math.sin(t + k)), 1) for k in range(3)]
    rpm = [round(v + (8 * math.sin(3 * t + k) if moving else 0), 1) for k, v in enumerate(sp)]
    j = dict(x=round(S["x"], 1), y=round(S["y"], 1), yaw=round(S["yaw"] % 360, 1), vx=0, vy=round(20 if moving else 0, 1),
             wz=0.0, mvx=0, mvy=round(20 if moving else 0, 1), mode=S["mode"], tgt=S["tgt"], spd=S["cs"][0], tb=False, arm=True,
             rt=0 if S["mode"] == 1 else -1, rs=1, rn=3, jg=S["jg"], rty=S["rty"], ps=S["ps"], pl=S["pl"], pr=S["pr"], sat=100 if S["sf"] == 0 else 70,
             np=S["np"], nd=S["nd"], pad=True, pt="Xbox", imu=True, bat=-1.0, tune=S["tune"], tw=S["tw"],
             rpm=rpm, sp=sp, d=[round(80 + 10 * math.sin(t), 1), round(40 + 5 * math.sin(2 * t), 1), -1], sa=[0, 90, -90],
             kp=S["kp"], ki=S["ki"], kd=S["kd"], dm=[round(80 + 10 * math.sin(t), 1), round(40 + 5 * math.sin(2 * t), 1), 999],
             dv=[True, True, False], wl=round(40 + 5 * math.sin(2 * t), 1), wr=999.0, wlr=40.0, wrr=0.0, wstat=1, ss=[3, 0, 1, 7],
             ia=40, ic=0xE8, **{"in": False}, mc=S["mc"], sf=S["sf"], so=S["so"], sfv=[1.0, 2.0, round(abs(math.sin(t)) * 30, 1), 55.0],
             cs=S["cs"], sv=S["sv"], dn=S["dn"], rts=S["rts"], terrain="FLAT", yaw_error=round(0.4 * math.sin(t), 2), stuck=False,
             slip=False, pose_degraded=False, encoder_trust=1.0, pitch=0.5, roll=-0.3,
             pid=dict(kp=1.2, ki=0.3, kd=0.05, status="DONE"), wp=S["wp"],
             gp=S.get("gp", False), slf=1.0, **{"as": 1.0}, re=round(40 + 5 * math.sin(t), 1), smin=round(14 + 2 * math.sin(t), 1), av=0, rst=False,
             arr=dict(q=2, id=2, n=1, v=1, rl=0.8, rf=-0.5, m=[20.6, -1, 99.5], r=[20, 0, 100]))
    j["zzz_random"] = random.random()
    return json.dumps(j)


async def send(w, data):
    b = data.encode(); n = len(b)
    h = bytes([0x81]) + (bytes([n]) if n < 126 else b"\x7e" + struct.pack(">H", n) if n < 65536 else b"\x7f" + struct.pack(">Q", n))
    w.write(h + b); await w.drain()


async def handle(r, w):
    try:
        req = (await r.readuntil(b"\r\n\r\n")).decode("latin1")
        key = next(l.split(":", 1)[1].strip() for l in req.split("\r\n") if l.lower().startswith("sec-websocket-key"))
        acc = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        w.write(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {acc}\r\n\r\n".encode())
        await w.drain()
    except Exception:
        w.close(); return
    print("klien tersambung:", w.get_extra_info("peername")); clients.add(w)
    S["last_hb"] = time.time(); S["connected_at"] = S["connected_at"] or time.time()
    try:
        while True:
            h = await r.readexactly(2); op = h[0] & 0x0F; ln = h[1] & 0x7F
            if ln == 126: ln = struct.unpack(">H", await r.readexactly(2))[0]
            elif ln == 127: ln = struct.unpack(">Q", await r.readexactly(8))[0]
            mask = await r.readexactly(4) if h[1] & 0x80 else b"\0\0\0\0"
            p = bytes(c ^ mask[i % 4] for i, c in enumerate(await r.readexactly(ln)))
            if op == 8: break
            if op == 9: w.write(b"\x8a" + bytes([len(p)]) + p); continue
            if op != 1: continue
            S["last_hb"] = time.time()
            try: c = json.loads(p)
            except Exception: print("pesan rusak dari klien:", p[:60]); continue
            if c.get("cmd") != "hb" or A.hb: print("CMD", p.decode())
            cmd(c)
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    finally:
        clients.discard(w); print("klien putus"); w.close()
        if not clients: S["connected_at"] = 0.0


async def ticker():
    n = 0; last = time.time()
    while True:
        await asyncio.sleep(0.1); now = time.time(); step(now - last); last = now
        if not clients: continue
        n += 1; msg = telemetry()
        if A.bad and n % A.bad == 0: msg = random.choice(["{rusak", "", "[1,2]", "{\"x\":", msg[: len(msg) // 2]])
        for w in list(clients):
            try: await send(w, msg)
            except Exception: clients.discard(w)


async def main():
    srv = await asyncio.start_server(handle, "0.0.0.0", A.port)
    print(f"mock robot di ws://0.0.0.0:{A.port}/ws (Ctrl+C berhenti)")
    async with srv: await ticker()

try: asyncio.run(main())
except KeyboardInterrupt: pass
