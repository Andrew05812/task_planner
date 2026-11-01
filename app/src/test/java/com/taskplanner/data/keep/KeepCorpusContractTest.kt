package com.taskplanner.data.keep

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corpus contract: the parser, run over a whole file shaped like the user's real export.
 *
 * **Why these numbers are here as constants.** They were measured on 2026-11-02 from `google-keep-original-export.txt`,
 * the user's own text, which is personal data and stays out of the repository (`.gitignore` names it). The
 * depersonalized file this test reads was generated from those measurements by
 * `scripts/make-keep-corpus-fixture.py`, which never opens the original. So the numbers are the source's and the
 * titles are not, which is the only arrangement that lets CI check a real shape without publishing anybody's life.
 *
 * **What would make this test lie, and what guards against it.** A contract that asserts against a constant baked
 * into the test — `assertEquals(382, SOMETHING)` — passes whether or not the file says so. So every number below is
 * counted out of the parsed document or out of the file's own bytes, and the file is read from disk on each run.
 * The last test is the privacy one: if a real title ever leaked into the fixture, it fails.
 */
class KeepCorpusContractTest {

    private val corpus: String by lazy {
        val classpath = javaClass.classLoader.getResourceAsStream(CORPUS_RESOURCE)
        if (classpath != null) return@lazy classpath.readBytes().toString(Charsets.UTF_8)
        // The IDE and Gradle disagree about where the test classpath ends; the project dir does not move.
        File(System.getProperty("taskplanner.project.dir") ?: ".", "src/test/resources/$CORPUS_RESOURCE")
            .also { assertTrue("corpus fixture found neither on the classpath nor at $it", it.isFile) }
            .readText()
    }

    private val doc: KeepParsedDocument by lazy { KeepTextParser.parse(corpus) }

    @Test
    fun theFileIsTheShapeTheUserPastedIntoTheApp() {
        val bytes = corpus.toByteArray(Charsets.UTF_8)
        assertEquals("439 physical lines, the count the user's own file reports", 439, doc.stats.physicalLines)
        assertEquals("CRLF throughout, because that is what the export writes", 438, bytes.count { it == '\r'.code.toByte() })
        assertEquals("no blank physical line in this source", 0, doc.stats.blankLines)
    }

    @Test
    fun everyTitledLineIsKeptAndNoneOfTheSeparatorsBecomesATask() {
        assertEquals(382, doc.stats.topics + doc.stats.tasks)
        assertEquals(382, doc.allNodes().size)
        assertEquals("57 empty checkbox rows, counted apart and imported as nothing", 57, doc.stats.separatorLines)
        assertEquals(0, doc.issues.size)
    }

    @Test
    fun theCompletedAndOpenCountsAreTheOnesInTheSource() {
        assertEquals(296, doc.stats.checked)
        assertEquals(86, doc.stats.unchecked)
        assertEquals("this file has no line without a box", 0, doc.stats.unknownStatus)
    }

    @Test
    fun rootLinesBecomeIndependentTopicsAndNotOneBigGroup() {
        // The single-group import is the mistake this stage exists to stop, so it is asserted on the corpus
        // itself: 56 roots, and the biggest of them holding 95 children rather than all 326.
        assertEquals(56, doc.stats.topics)
        assertEquals(326, doc.stats.tasks)
        assertEquals(37, doc.topics.count { it.children.isNotEmpty() })
        assertEquals(19, doc.topics.count { it.children.isEmpty() })
        assertEquals(95, doc.topics.maxOf { it.children.size })
    }

    @Test
    fun aTopicsOwnCheckboxStateSurvivesAndStaysSeparateFromItsChildren() {
        // 34 of the 56 topics are `[X]` in the source. Nothing in today's schema can hold that, which is the
        // reason `task_groups.is_completed` is part of this stage rather than a later one.
        assertEquals(34, doc.topics.count { it.check == KeepCheck.CHECKED })
        assertEquals(262, doc.allNodes().count { it.depth == 1 && it.check == KeepCheck.CHECKED })
        assertTrue(
            "a completed topic does not complete its open children",
            doc.topics.filter { it.check == KeepCheck.CHECKED }.any { it.children.any { c -> c.check == KeepCheck.UNCHECKED } }
        )
    }

    @Test
    fun theSourceOnlyGoesTwoLevelsDeepAndTheParserReadsExactlyThat() {
        // Levels three and four are proven in `KeepTextParserTest`; claiming them from this file would be a
        // invention, so the corpus test asserts the opposite: the user's own export stops at depth 1.
        assertEquals(1, doc.stats.maxDepth)
        assertEquals(listOf(0, 1), doc.allNodes().map { it.depth }.distinct().sorted())
    }

    @Test
    fun repeatedTitlesStayRepeatedInTheTree() {
        val counts = doc.allNodes().groupingBy { it.title }.eachCount()
        assertEquals(19, counts.values.count { it > 1 })
        assertEquals(36, counts.values.filter { it > 1 }.sumOf { it - 1 })
    }

    @Test
    fun theLongestLineIsKeptWhole() {
        assertEquals(169, doc.allNodes().maxOf { it.title.length })
    }

    @Test
    fun theFixtureCarriesNoRealNoteFromTheSource() {
        // Privacy, checked rather than promised: every title in the corpus comes from the generator's vocabulary
        // (`Topic NN`, `Item NNN`, `Repeated errand NN`) plus the decorations it adds. Anything else in this file
        // means somebody's real note got committed, and CI would be publishing it.
        val allowed = Regex("^(🟥|🟩|🟦|⭐|📌|✅|🔁)?(Topic \\d\\d|Item \\d\\d\\d|Repeated errand \\d\\d)( — .*| - .*)?$")
        val offenders = doc.allNodes().map { it.title }.filter { !allowed.matches(it.trim()) }
        assertTrue("titles that are not from the generator's vocabulary: ${offenders.take(3)}", offenders.isEmpty())
    }

    private companion object {
        const val CORPUS_RESOURCE = "keep/keep-corpus-shaped-like-the-original.txt"
    }
}
