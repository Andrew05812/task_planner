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
 */
class FakeTimeBase(
    var wall: Long = 0L,
    var monotonic: Long = 0L
) : TimeBase {

    override fun wallMillis(): Long = wall

    override fun monotonicMillis(): Long = monotonic

    /** Pass real time: both clocks move by the same amount unless the caller says otherwise. */
    fun advance(millis: Long) {
        wall += millis
        monotonic += millis
    }

    /** The system clock is moved by [millis] while the monotonic clock stands still. */
    fun shiftWall(millis: Long) {
        wall += millis
    }
}
