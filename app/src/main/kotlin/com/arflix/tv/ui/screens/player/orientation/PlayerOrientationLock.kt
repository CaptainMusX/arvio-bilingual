package com.arflix.tv.ui.screens.player.orientation

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper

/**
 * Process-wide owner of the player's forced-landscape orientation.
 *
 * Before (upstream bug): every PlayerScreen remembered `activity.requestedOrientation` on entry
 * and restored it on exit. When the next episode auto-plays, the new player composes while the
 * old one is still alive, so the new one remembers SENSOR_LANDSCAPE as "the orientation before
 * the player"; the old one then disposes and restores portrait (→ the new episode flips to
 * portrait), and leaving the new player "restores" landscape (→ the app stays landscape).
 *
 * Now every player holds a [Lease]. The orientation from before the FIRST lease is saved once
 * and restored only when the LAST lease is released; overlapping players keep landscape. The
 * restore is deferred a moment so a dispose → recompose hand-over doesn't rotate the screen.
 */
object PlayerOrientationLock {
    private const val RESTORE_DELAY_MS = 350L

    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashSet<Lease>()
    private var savedOrientation: Int? = null
    private var pendingRestore: Runnable? = null

    class Lease internal constructor() {
        @Volatile internal var released = false
    }

    fun acquire(activity: Activity?): Lease {
        val lease = Lease()
        pendingRestore?.let { main.removeCallbacks(it) }
        pendingRestore = null
        if (active.isEmpty() && savedOrientation == null) {
            val current = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            // A stale landscape from a crashed/abandoned session is never the "before" state.
            savedOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER.takeIf { !isTvLike(activity) } ?: current
            } else current
        }
        active += lease
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        return lease
    }

    /** Idempotent. [immediate] restores without the hand-over delay (explicit exit). */
    fun release(activity: Activity?, lease: Lease, immediate: Boolean = false) {
        if (lease.released) return
        lease.released = true
        active -= lease
        if (active.isNotEmpty()) return
        val restore = Runnable {
            pendingRestore = null
            if (active.isEmpty()) {
                val target = savedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                savedOrientation = null
                activity?.requestedOrientation = target
            }
        }
        pendingRestore?.let { main.removeCallbacks(it) }
        if (immediate) {
            restore.run()
        } else {
            pendingRestore = restore
            main.postDelayed(restore, RESTORE_DELAY_MS)
        }
    }

    private fun isTvLike(activity: Activity?): Boolean {
        val pm = activity?.packageManager ?: return false
        return pm.hasSystemFeature("android.software.leanback")
    }
}
