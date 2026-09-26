#!/usr/bin/env python3
"""Power-cycle a camera through a Tuya / Smart Life smart plug, over the LAN only (no cloud).

    camera-power.py status          print the plug's data points
    camera-power.py cycle [SECS]    cut power, let the PLUG restore it after SECS (default 10)

Used as CAM_POWER_CMD by record-preroll.sh when the camera's RTSP service wedges (pings, but serves
no video) -- a state only a real power cut clears.

Fail-safe by design: we never send "on". We switch the plug off and arm its own countdown timer
(dp 9), and the plug turns itself back on. If this script, the NVR phone or the Wi-Fi dies halfway,
the camera still gets its power back. Verified 2026-09-25: restored by the plug after ~11 s. The one
gap is "off" landing but the countdown not; that case is rescued by turning it straight back on.

Config (private, never committed): ~/.camera-plug.json
    {"id": "...", "key": "<local_key>", "ip": "192.168.101.22", "version": 3.5}
Exit: 0 = cycled and the plug reported back on; 1 = failed (plug untouched or rescued); 2 = usage.
"""
import json, os, sys, time
import tinytuya

CONF = os.environ.get("CAMERA_PLUG_CONF", os.path.expanduser("~/.camera-plug.json"))
DP_SWITCH, DP_COUNTDOWN = "1", "9"


def log(msg):
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} [plug] {msg}", flush=True)


def connect(c):
    dev = tinytuya.OutletDevice(c["id"], c["ip"], c["key"], version=float(c.get("version", 3.5)))
    dev.set_socketTimeout(5)
    dev.set_socketRetryLimit(3)
    return dev


def dps(dev):
    st = dev.status()
    if not isinstance(st, dict) or "dps" not in st:
        raise RuntimeError(f"no status: {st}")
    return st["dps"]


def open_plug(c):
    """Connect at the configured IP; if the plug moved (DHCP), find it by device id and retry."""
    try:
        dev = connect(c)
        return dev, dps(dev)
    except Exception as e:
        log(f"not answering at {c['ip']} ({e}); scanning the LAN for it")
    found = tinytuya.find_device(dev_id=c["id"])
    if not found or not found.get("ip"):
        raise RuntimeError("plug not found on the LAN")
    c = dict(c, ip=found["ip"])
    log(f"found at {c['ip']} (update {CONF} / reserve the IP in the router)")
    dev = connect(c)
    return dev, dps(dev)


def cycle(dev, secs):
    before = dps(dev)
    if before.get(DP_SWITCH) is not True:
        # The plug is already off (someone switched it, or a previous cycle is mid-flight). Arming a
        # countdown on an OFF plug would turn it ON -- fine -- but we did not cause this state, so
        # restore power plainly and report it instead of pretending we cycled anything.
        log(f"plug was already OFF ({before}); switching it on")
        dev.set_value(int(DP_SWITCH), True)
        return 1
    dev.set_value(int(DP_SWITCH), False)
    try:
        r = dev.set_value(int(DP_COUNTDOWN), int(secs))
        if not isinstance(r, dict) or str(r.get("dps", {}).get(DP_COUNTDOWN)) != str(int(secs)):
            raise RuntimeError(f"countdown not acknowledged: {r}")
    except Exception as e:
        log(f"!! {e} -- rescuing: switching straight back on")
        for _ in range(5):
            try:
                dev.set_value(int(DP_SWITCH), True)
                if dps(dev).get(DP_SWITCH) is True:
                    log("rescued: plug back on")
                    break
            except Exception:
                time.sleep(2)
        return 1
    log(f"power cut; the plug restores it in {secs}s")
    deadline = time.time() + secs + 25
    while time.time() < deadline:
        time.sleep(2)
        try:
            if dps(dev).get(DP_SWITCH) is True:
                log("plug reports power back on")
                return 0
        except Exception:
            pass                      # busy or rebooting its radio; keep polling until the deadline
    log("!! the plug has not reported ON yet (its own timer should still restore it)")
    return 1


def main(argv):
    if len(argv) < 2 or argv[1] not in ("status", "cycle"):
        print(__doc__)
        return 2
    with open(CONF, encoding="utf-8") as f:
        c = json.load(f)
    try:
        dev, now = open_plug(c)
    except Exception as e:
        log(f"!! cannot reach the plug: {e}")
        return 1
    if argv[1] == "status":
        print(json.dumps(now))
        return 0
    secs = int(argv[2]) if len(argv) > 2 else 10
    return cycle(dev, secs)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
