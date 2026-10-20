package com.taskplanner.testing

import com.taskplanner.data.tracking.TimeBase

/**
 * Both clocks under the test's control, so a clock change is something a test causes rather than
 * something that happens to it.
 *
 * `wall` is the instant that gets stored and can be jumped forwards or backwards exactly the way the
 * user setting their system clock does. `monotonic` only ever advances — it is elapsed real time, so
 * there is no way to move it — and the two are deliberately free to disagree, which is the whole
 * subject of [com.taskplanner.data.tracking.WorkTimeMath.measureInterval].
 *
 * Because that freedom to disagree is a feature, the wall line cannot be assigned from outside. A test
 * that wants the scenario to sit on another day has to move both clocks together with [shiftTo]; a test
 * that wants the user to have changed the system clock says so with [shiftWall]. Setting one column alone
 * is how a day-anchor test silently becomes a clock-change test, and
 * [com.taskplanner.data.tracking.WorkTimeMath.measureInterval] then answers the second question —
 * reporting the monotonic figure and an anomaly — while the assertions still expect the
 * first. The private setter keeps that confusion out of the fixture now.
 */
class FakeTimeBase(
    wall: Long = 0L,
    monotonic: Long = 0L
) : TimeBase {

    /** Where the stored instants are read from: readable by any assertion, movable only by the methods below. */
    var wall: Long = wall
        private set

    var monotonic: Long = monotonic
        private set

    override fun wallMillis(): Long = wall

    override fun monotonicMillis(): Long = monotonic

    /** Pass real time: both clocks move by the same amount unless the caller says otherwise. */
    fun advance(millis: Long) {
        wall += millis
        monotonic += millis
    }

    /**
     * Put the whole timeline at [wallMillis], keeping the two clocks in step.
     *
     * This is the day anchor: the scenario runs on the day named rather than on the day the Gradle runner
     * happened to wake up, and every duration measured afterwards comes out the length the test chose. The
     * monotonic line moves by the same delta, so the two stay a single consistent timeline and
     * [com.taskplanner.data.tracking.MonotonicReference] finds nothing to report. It may read negative —
     * the clock was never anchored at boot, only relative to the instant the fixture was built, and every
     * reader of it subtracts rather than compares it to zero.
     */
    fun shiftTo(wallMillis: Long) {
        val delta = wallMillis - wall
        wall = wallMillis
        monotonic += delta
    }

    /** The system clock is moved by [millis] while the monotonic clock stands still. */
    fun shiftWall(millis: Long) {
        wall += millis
    }
}
