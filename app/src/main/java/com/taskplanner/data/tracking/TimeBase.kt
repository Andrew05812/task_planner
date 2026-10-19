package com.taskplanner.data.tracking

import android.os.SystemClock

/**
 * The two clocks a measurement needs, behind one seam so tests can hold both.
 *
 * [wallMillis] is the instant that gets stored — it is comparable across devices, reboots and
 * syncs, and it is the only one of the two that a database row can mean anything with.
 * [monotonicMillis] is the one that cannot be moved by the user, by a time-zone change, or by an
 * NTP correction, and so is the only honest way to answer "how long has this been running *for
 * me*" while the interval is still open. Keeping both injectable is what lets
 * [WorkTimeMath.measureInterval] decide between them from real readings instead of assuming the
 * wall clock was untouched, which is exactly the case a clock change would otherwise corrupt.
 */
interface TimeBase {
    /** UTC epoch millis, as stored in every instant column. */
    fun wallMillis(): Long

    /** Millis since boot, excluding deep sleep — the elapsed real time clock. */
    fun monotonicMillis(): Long
}

/** The device's own clocks. */
class AndroidTimeBase : TimeBase {
    override fun wallMillis(): Long = System.currentTimeMillis()

    // elapsedRealtime stops counting while the device is asleep, which is the right answer for
    // "did I work on this": an overnight pause between two taps is not work, and a timer that
    // kept running through sleep would charge the user for their night.
    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * Translates a stored wall instant into this process's monotonic line.
 *
 * The two clocks are only comparable at a single point: the instant this object was created, where
 * both were read together. Everything before that point cannot be located on the monotonic line at
 * all — the device may have rebooted, in which case elapsedRealtime restarted from zero — so
 * [elapsedSince] returns null rather than a number, and the caller falls back to the wall clock and
 * records that it did. That is the compromise the design accepts: a live ticker is smoothed by the
 * monotonic clock whenever this process saw the interval begin, and an interval that predates this
 * process is measured by the wall clock alone, with a clock change made visible instead of hidden.
 */
class MonotonicReference(private val timeBase: TimeBase) {

    private val syncWall: Long = timeBase.wallMillis()
    private val syncMono: Long = timeBase.monotonicMillis()

    /**
     * How many milliseconds have passed since [startedAtWallMillis] by this process's monotonic
     * clock, or null when that instant is outside the reach of this clock.
     */
    fun elapsedSince(startedAtWallMillis: Long): Long? {
        if (startedAtWallMillis < syncWall) return null
        val startedMono = syncMono + (startedAtWallMillis - syncWall)
        val elapsed = timeBase.monotonicMillis() - startedMono
        return if (elapsed < 0) null else elapsed
    }
}
