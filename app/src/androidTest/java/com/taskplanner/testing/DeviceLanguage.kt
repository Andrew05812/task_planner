package com.taskplanner.testing

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario

/**
 * Putting the app back into the device's own language, and proving it happened.
 *
 * **Why a class needs to ask for this.** Stage 14 made the interface language a reader's choice, and the choice
 * is stored by the framework rather than in the app's own files — so the instrumented runner's
 * `clearPackageData`, which takes the database and the preferences away between classes, does *not* take the
 * language away. A class that chooses Russian and does not hand it back leaves every later class reading
 * Russian words: `TaskCardWidthBudgetInstrumentedTest` measured 136px against spec §4.1's 84dp allowance, and
 * the allowance is a budget for *"Oct 25, 2026"* while the card was drawing *"25 окт. 2026 г."* — a correct
 * measurement of a different row. The failure was the harness's, not the card's.
 *
 * **Why it needs a window.** With no AppCompat host alive, `setApplicationLocales` is a silent no-op — the
 * premise [com.taskplanner.data.report.export.CsvLocaleTest] measures and the matrix this file came out of was
 * caused by it twice. So [restore] is only ever called after an activity has been launched, and it waits for
 * the delegate to answer the empty list, which is what "the device's language" means to the framework.
 */
object DeviceLanguage {

    /** True once the app holds no language of its own again. */
    fun <A : Activity> restore(scenario: ActivityScenario<A>, withinMillis: Long = WAIT_MILLIS): Boolean {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        val deadline = System.currentTimeMillis() + withinMillis
        while (System.currentTimeMillis() < deadline) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) return true
            Thread.sleep(100)
        }
        return AppCompatDelegate.getApplicationLocales().isEmpty
    }

    /** The words a failing premise should be reported with. */
    fun message(also: String): String =
        "precondition, not a screen defect: $also. A measurement taken in one language against a budget " +
            "written for another is not evidence about either."

    private const val WAIT_MILLIS = 30_000L
}
