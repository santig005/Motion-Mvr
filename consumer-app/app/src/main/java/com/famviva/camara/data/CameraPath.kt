package com.famviva.camara.data

/**
 * Which camera a Drive file belongs to, derived from its folder path.
 *
 * The NVR uploads `<Cameras root>/<Camara1>/YYYY/MM/DD/mt_*.mp4` (and `<Camara1>/metrics.csv`), but a
 * Drive file only knows its immediate parent — the DD folder. The camera is therefore the first
 * ancestor whose name is NOT a date component: walk up past the all-digit YYYY/MM/DD folders and
 * take the first named one. This also holds for files sitting directly in the camera folder
 * (metrics.csv), and it never depends on what the root folder is called.
 *
 * The camera id is the folder name (`Camara1`) — the same id the NVR writes into every telemetry line
 * and status.json, so clips and health line up without any mapping.
 */
data class DriveFolder(val name: String, val parent: String?)

/** Walk budget: YYYY/MM/DD is three levels; anything deeper than this is not our layout. */
private const val MAX_DATE_DEPTH = 6

/** The camera owning a file whose parent folder is [folderId], or null when unknown (folder not in
 *  [folders], a cycle, or no named ancestor) — callers then treat the clip as camera-less, which is
 *  exactly today's single-camera behaviour. */
fun cameraOfFolder(folderId: String?, folders: Map<String, DriveFolder>): String? {
    var id = folderId
    repeat(MAX_DATE_DEPTH + 1) {
        val f = folders[id ?: return null] ?: return null
        if (!f.name.all { it.isDigit() }) return f.name
        id = f.parent
    }
    return null
}
