package com.taskplanner.data.tracking

import com.taskplanner.data.calendar.CalendarMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The one figure that is read rather than stored: the seconds a stretch has been running.
 *
 * Nothing here is a source of truth. The start instant comes from the interval row, and this class only
 * re-measures it once a second while a screen is looking at it — when the stretch ends the flow has no
 * value to produce and the loop is gone, so an app that is open all night is not holding a coroutine
 * that remembers a timer nobody started.
 */
@Singleton
class WorkTicker @Inject constructor(
    private val timeBase: TimeBase,
    private val monotonic: MonotonicReference
) {

    /**
     * Seconds so far for the stretch that started at [openStartedAt] in wall-clock milliseconds, emitted
     * once immediately and then every second. A null start means nothing is open and yields a single
     * null: no loop, no tick, no work for the screen to redraw.
     *
     * The measurement is the same one the closed row will get when the stretch ends, monotonic clock
     * included, so the number on screen and the number in history cannot disagree.
     */
    fun secondsSince(openStartedAt: Long?): Flow<Long?> = flow {
        if (openStartedAt == null) {
            emit(null)
            return@flow
        }
        while (currentCoroutineContext().isActive) {
            emit(
                WorkTimeMath.openSeconds(
                    openSince = openStartedAt,
                    nowMillis = timeBase.wallMillis(),
                    monotonicElapsedMillis = monotonic.elapsedSince(openStartedAt)
                )
            )
            delay(TICK_INTERVAL_MILLIS)
        }
    }

    /**
     * Today, and then today again when it stops being yesterday.
     *
     * A screen that shows "today" has to move its figure at midnight even while nothing is measured, and
     * the day boundary is the one moment it can be told about in advance: the collector sleeps until the
     * next local midnight rather than polling the clock. The instant it sleeps to is read from the
     * injected time base, so a test can put a day change anywhere it likes.
     */
    fun localDays(zone: ZoneId): Flow<LocalDate> = flow {
        while (currentCoroutineContext().isActive) {
            val now = timeBase.wallMillis()
            val today = WorkTimeMath.toDate(WorkTimeMath.epochDayOf(now, zone))
            emit(today)
            val nextMidnight = CalendarMath.startOfDay(today.plusDays(1), zone)
            // A clock the user moved can put the boundary behind us; sleeping a minute and re-deriving
            // the day is what keeps this from turning into a loop that never sleeps.
            delay((nextMidnight - now).coerceAtLeast(RECHECK_AFTER_ANOMALY_MILLIS))
        }
    }

    companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
        private const val RECHECK_AFTER_ANOMALY_MILLIS = 60_000L
    }
}
