package com.mediaviewer.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Leaves a note on disk right before risky NATIVE work (Filament) and
 * removes it right after. Filament reports misuse by aborting the process
 * — no Java exception, so the normal crash handler never runs and the app
 * just vanishes. If the note is still there on the next launch, the process
 * died during that step, and the crash screen can say exactly where.
 *
 * Also lets VRM mode skip the step that killed the previous run (e.g.
 * texturing), so one bad avatar can't make the page unusable.
 */
object CrashBreadcrumbs {
    private const val FILE = "native_step.txt"
    /** Prefix of every VRM texturing step (see [skipVrmTextures]). */
    const val VRM_TEXTURE_STEP = "VRM texture"
    private var file: File? = null

    /** The step that was running when the previous process died, if any.
     *  Captured once at startup. */
    var previousRunDiedDuring: String? = null
        private set

    /**
     * How VRM textures are uploaded. Each native crash while texturing
     * steps this down one level and it STAYS there (saved to disk), so the
     * app settles on the most capable mode this device survives:
     *  0 = sRGB, single level, texture + UV set + UV matrix (the mode that
     *      survived on device; the old mipmapped mode is gone)
     *  1 = plain RGBA8, texture + UV set only
     *  2 = no textures
     * Picking a different avatar starts again from 0.
     */
    var vrmTextureMode = 0
        private set
    val skipVrmTextures get() = vrmTextureMode >= VRM_TEXTURE_MODE_OFF
    const val VRM_TEXTURE_MODE_OFF = 2
    private var modeFile: File? = null

    fun resetVrmTextureMode() {
        vrmTextureMode = 0
        runCatching { modeFile?.delete() }
    }

    /** Android's own record of how the previous run ended (API 30+). */
    var previousExitReason: String? = null
        private set

    fun init(context: Context) {
        val f = File(context.filesDir, FILE)
        file = f
        previousRunDiedDuring = runCatching { if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null }.getOrNull()
        runCatching { f.delete() }
        val mf = File(context.filesDir, "vrm_texture_mode_v2.txt") // v2: fresh start with the new mode numbering
        modeFile = mf
        vrmTextureMode = runCatching { mf.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, VRM_TEXTURE_MODE_OFF)
        if (previousRunDiedDuring?.startsWith(VRM_TEXTURE_STEP) == true && vrmTextureMode < VRM_TEXTURE_MODE_OFF) {
            vrmTextureMode++
            runCatching { mf.writeText(vrmTextureMode.toString()) }
        }
        if (previousRunDiedDuring != null && Build.VERSION.SDK_INT >= 30) {
            previousExitReason = runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.let { info ->
                    "${reasonName(info.reason)}${info.description?.let { " — $it" } ?: ""} (pss ${info.pss / 1024} MB)"
                }
            }.getOrNull()
        }
    }

    /** Runs [block] with [step] recorded as "in progress". */
    inline fun <T> around(step: String, block: () -> T): T {
        mark(step)
        try {
            return block()
        } finally {
            clearMark()
        }
    }

    fun mark(step: String) {
        runCatching { file?.writeText(step) }
    }

    fun clearMark() {
        runCatching { file?.delete() }
    }

    /** Text for the crash screen when the Java handler recorded nothing. */
    fun report(): String? {
        val step = previousRunDiedDuring ?: return null
        return buildString {
            append("The app was killed without a Java exception (native crash or out of memory).\n\n")
            append("It died during: ").append(step).append('\n')
            previousExitReason?.let { append("Android exit reason: ").append(it).append('\n') }
            if (step.startsWith(VRM_TEXTURE_STEP)) {
                append("\nVRM textures now use safe mode $vrmTextureMode of $VRM_TEXTURE_MODE_OFF")
                append(if (vrmTextureMode >= VRM_TEXTURE_MODE_OFF) " (textures off).\n" else ".\n")
            }
        }
    }

    fun dismissReport() {
        previousRunDiedDuring = null
        previousExitReason = null
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        else -> "exit reason $reason"
    }
}
