#!/data/data/com.termux/files/usr/bin/sh
# boot-start-cam.sh — ROBUST NVR startup after powering on / rebooting the phone.
# Termux:Boot runs this file (copied to ~/.termux/boot/start-cam.sh) when the device boots.
# It can also be run by hand at any time (idempotent: it never duplicates anything).
#
# MANUAL STEPS (one time, only you can do these on the phone):
#   1) Install the "Termux:Boot" app from F-Droid (SAME source as Termux) and OPEN it once.
#   2) Remove battery optimization for Termux and Termux:Boot (Android Settings -> Apps -> Battery
#      -> Unrestricted), so the system doesn't kill them.
#   3) Keep the phone ALWAYS PLUGGED IN (an NVR must not depend on the battery). Optional:
#      Developer options -> "Stay awake while charging".
#   4) Copy it to the boot location:
#        mkdir -p ~/.termux/boot && cp ~/boot-start-cam.sh ~/.termux/boot/start-cam.sh && chmod +x ~/.termux/boot/start-cam.sh
#
# What it does: wake-lock + sshd + launches the WATCHDOG, which in turn keeps cam1 and cloud alive
# (and revives them if they die). All the resilience lives in the watchdog; this just turns it on.

termux-wake-lock
sleep 8                                        # give Wi-Fi time to come up after boot

# sshd so you can always administer (the watchdog also guards it)
pgrep -x sshd >/dev/null 2>&1 || sshd

# One watchdog: it takes care of cam1 and cloud. Runs in its own tmux session.
tmux has-session -t watch 2>/dev/null || tmux new-session -d -s watch "cd ~ && exec ./watchdog.sh"
