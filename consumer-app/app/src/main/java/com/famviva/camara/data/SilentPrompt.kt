package com.famviva.camara.data

/**
 * "Did you unplug it?" (multi-camera, decided 2026-08-02). A camera that stays silent is either
 * unplugged on purpose (the user forgot to switch it off first) or a real incident (cut cable, theft,
 * power loss). The system must never decide which: it ASKS, once per episode, after [SILENT_ASK_SECS],
 * with [Disable] / [Still installed]. It never auto-disables — that is exactly how a stolen camera
 * would go quiet forever.
 */
const val SILENT_ASK_SECS = 6 * 3600L

/** One camera's silence episode: when it started, whether the prompt was shown, and whether the user
 *  answered "still installed" (then we stop asking until the episode ends). */
data class SilentState(val since: Long, val asked: Boolean = false, val keep: Boolean = false)

/**
 * "Silent" for this prompt = the NVR is alive and reporting (fresh status) but THIS camera is not
 * recording, and not because it is wedged (a wedged camera pings — it needs a power-cycle, not a
 * switch-off) nor already switched off. A stale status means the NVR phone itself is down: asking
 * "did you unplug the camera?" then would point the user at the wrong device.
 */
fun isSilentForPrompt(h: CameraHealth, now: Long): Boolean =
    !h.ok && !h.isStale(now) && !h.cameraWedged && !h.disabled

/**
 * Advances every camera's episode by one poll. [silent] = the cameras silent right now. Returns the
 * new states (a camera no longer silent drops out: its episode is over) and the cameras to ask about
 * NOW (silent for at least [askAfter], not asked yet, not answered "still installed").
 */
fun stepSilent(
    prev: Map<String, SilentState>,
    silent: Set<String>,
    now: Long,
    askAfter: Long = SILENT_ASK_SECS,
): Pair<Map<String, SilentState>, List<String>> {
    val next = LinkedHashMap<String, SilentState>()
    val ask = mutableListOf<String>()
    for (id in silent.sorted()) {
        val st = prev[id] ?: SilentState(since = now)
        if (!st.asked && !st.keep && now - st.since >= askAfter) {
            ask += id
            next[id] = st.copy(asked = true)
        } else {
            next[id] = st
        }
    }
    return next to ask
}

/** Compact persistence ("id|since|asked|keep" per line) — no org.json, so it is unit-testable. */
fun encodeSilent(states: Map<String, SilentState>): String =
    states.entries.joinToString("\n") { (id, s) -> "$id|${s.since}|${if (s.asked) 1 else 0}|${if (s.keep) 1 else 0}" }

fun decodeSilent(raw: String?): Map<String, SilentState> =
    raw.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('|')
        val since = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        if (p.size < 4 || p[0].isBlank()) return@mapNotNull null
        p[0] to SilentState(since, asked = p[2] == "1", keep = p[3] == "1")
    }.toMap()
