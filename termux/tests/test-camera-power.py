#!/usr/bin/env python3
"""Tests for camera-power.py's cycle() against a fake plug. The real plug can't be made to fail on
demand, and the failure paths are the ones that could leave the camera without power.
Run: python termux/tests/test-camera-power.py
"""
import importlib.util, os, sys, types

HERE = os.path.dirname(os.path.abspath(__file__))
sys.modules.setdefault("tinytuya", types.ModuleType("tinytuya"))   # cycle() never touches the library
spec = importlib.util.spec_from_file_location("camera_power", os.path.join(HERE, "..", "camera-power.py"))
cp = importlib.util.module_from_spec(spec); spec.loader.exec_module(cp)
cp.time.sleep = lambda s: None
cp.log = lambda m: None


class FakePlug:
    """Tuya countdown semantics: when it expires the switch flips. `ack_countdown=False` models the
    dangerous case -- 'off' lands but the countdown doesn't."""
    def __init__(self, on=True, ack_countdown=True):
        self.on, self.ack, self.countdown, self.calls = on, ack_countdown, 0, []

    def set_value(self, dp, v):
        self.calls.append((dp, v))
        if dp == 1:
            self.on = v
            return {"dps": {"1": v}}
        if dp == 9:
            if not self.ack:
                return {"Error": "Network Error: Device Unreachable", "Err": "905"}
            self.countdown = v
            return {"dps": {"9": v}}

    def status(self):
        if self.countdown:                       # the plug's own timer fires between polls
            self.on, self.countdown = not self.on, 0
        return {"dps": {"1": self.on, "9": self.countdown}}


PASS = FAIL = 0
def eq(label, want, got):
    global PASS, FAIL
    if want == got: PASS += 1; print(f"  ✓ {label}")
    else: FAIL += 1; print(f"  ✗ {label}\n      expected: {want}\n      actual:   {got}")

p = FakePlug()
eq("a normal cycle succeeds", 0, cp.cycle(p, 10))
eq("…power ends up on", True, p.on)
eq("…and we never sent 'on' ourselves (the plug's timer did)", False, (1, True) in p.calls)

p = FakePlug(ack_countdown=False)
eq("an unacknowledged countdown is a failure", 1, cp.cycle(p, 10))
eq("…and the camera is NOT left without power", True, p.on)

p = FakePlug(on=False)
eq("a plug already off is not 'cycled'", 1, cp.cycle(p, 10))
eq("…it is simply switched back on", True, p.on)
eq("…without arming a countdown", False, any(dp == 9 for dp, _ in p.calls))

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
