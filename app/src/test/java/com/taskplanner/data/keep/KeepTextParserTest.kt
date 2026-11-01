package com.taskplanner.data.keep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Google Keep text export means.
 *
 * The whole rule the user asked for, in one sentence: **a line with no indentation is a topic of its own, and a
 * line with indentation is a task of the nearest thing above it that can hold it** — up to four levels deep.
 * Everything below is a consequence of that, and the reason the parser is pure Kotlin: the one thing this stage
 * must never do is decide it by trial and error against a live database.
 *
 * **The corpus this file is written against.** The real export measured on 2026-11-02 is 439 physical lines,
 * 382 titled checkbox items (296 `[X]`, 86 `[ ]`), 57 empty checkbox rows, 56 root topics of which 37 have
 * children, one space of indentation, and a maximum depth of two levels. Two consequences shape these tests:
 * the source itself never goes below level two, so levels three and four are proven here from the shape the
 * user described rather than from their file; and no line in it lacks a checkbox, so that path is proven here
 * too. A contract the corpus cannot exercise is still a contract — it just has to say where its example comes
 * from, which each of those tests does.
 */
class KeepTextParserTest {

    private val topicAlpha = "Alpha"
    private val topicBeta = "Beta"

    // ---------------------------------------------------------------- the structure rule

    @Test
    fun aLineWithNoIndentationStartsATopicOfItsOwn() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n[ ] $topicBeta")

        assertEquals("two root lines are two topics, not one topic and a child", 2, doc.topics.size)
        assertEquals(topicAlpha, doc.topics[0].title)
        assertEquals(topicBeta, doc.topics[1].title)
        assertEquals("a topic has no parent, so it has no tasks beside its own children", 0, doc.stats.tasks)
    }

    @Test
    fun anIndentedLineBecomesATaskOfTheTopicAboveIt() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n [ ] first child\n [ ] second child")

        assertEquals(1, doc.topics.size)
        assertEquals(listOf("first child", "second child"), doc.topics[0].children.map { it.title })
        assertEquals(2, doc.stats.tasks)
    }

    @Test
    fun aRootLineAfterChildrenStartsAFreshTopicAndTakesNothingWithIt() {
        val doc = KeepTextParser.parse(
            "[ ] $topicAlpha\n [ ] child of alpha\n[ ] $topicBeta\n [ ] child of beta"
        )

        assertEquals(2, doc.topics.size)
        assertEquals(listOf("child of alpha"), doc.topics[0].children.map { it.title })
        assertEquals(listOf("child of beta"), doc.topics[1].children.map { it.title })
    }

    @Test
    fun fourLevelsSurviveAsATreeAndNotAsAFlatList() {
        // The shape the user specified. Their own file stops at level two, so this is the contract's example.
        val doc = KeepTextParser.parse(
            "[ ] Work\n" +
                " [ ] Project\n" +
                "  [ ] Backend\n" +
                "   [X] Implement the API\n" +
                "   [ ] Cover it with tests\n" +
                "  [ ] Android\n" +
                "   [ ] Settings screen\n" +
                " [ ] Documentation"
        )

        val work = doc.topics.single()
        assertEquals(listOf("Project", "Documentation"), work.children.map { it.title })
        val project = work.children.first()
        assertEquals(listOf("Backend", "Android"), project.children.map { it.title })
        val backend = project.children.first()
        assertEquals(
            "the two grandchildren belong to Backend, in source order",
            listOf("Implement the API", "Cover it with tests"),
            backend.children.map { it.title }
        )
        assertEquals("the fourth level is the deepest one the contract allows", 3, doc.stats.maxDepth)
        assertEquals("1 topic + 3 tasks + 4 nested = 8 titled lines", 8, doc.stats.topics + doc.stats.tasks)
    }

    @Test
    fun aSiblingBranchReturnsToItsParentAndKeepsItsOwnChildren() {
        val doc = KeepTextParser.parse(
            "[ ] root\n [ ] branch one\n  [ ] leaf one\n [ ] branch two\n  [ ] leaf two"
        )

        val root = doc.topics.single()
        assertEquals(listOf("branch one", "branch two"), root.children.map { it.title })
        assertEquals(listOf("leaf one"), root.children[0].children.map { it.title })
        assertEquals("a leaf does not migrate to the next sibling", listOf("leaf two"), root.children[1].children.map { it.title })
    }

    // ---------------------------------------------------------------- checkbox syntax

    @Test
    fun checkedAndUncheckedBoxesCarryTheirOwnState() {
        val doc = KeepTextParser.parse("[X] done thing\n[ ] open thing")

        assertEquals(KeepCheck.CHECKED, doc.topics[0].check)
        assertEquals(KeepCheck.UNCHECKED, doc.topics[1].check)
        assertEquals(1, doc.stats.checked)
        assertEquals(1, doc.stats.unchecked)
    }

    @Test
    fun aLowercaseXMeansTheSameThingAsAnUppercaseOne() {
        val doc = KeepTextParser.parse("[x] done quietly")

        assertEquals(KeepCheck.CHECKED, doc.topics[0].check)
    }

    @Test
    fun aTopicCarriesItsOwnStateAndDoesNotGiveItToItsChildren() {
        // §9: the source marks topics `[X]` too, and the states of parent and child are independent facts.
        val doc = KeepTextParser.parse("[X] finished topic\n [ ] open child\n [X] finished child")

        val topic = doc.topics.single()
        assertEquals(KeepCheck.CHECKED, topic.check)
        assertEquals(KeepCheck.UNCHECKED, topic.children[0].check)
        assertEquals(KeepCheck.CHECKED, topic.children[1].check)
    }

    @Test
    fun bracketsInsideATitleArePartOfTheTitle() {
        val doc = KeepTextParser.parse("[ ] call [the office] about [item 3]")

        assertEquals("call [the office] about [item 3]", doc.topics.single().title)
    }

    // ---------------------------------------------------------------- the empty lines

    @Test
    fun anEmptyCheckboxRowIsASeparatorAndNeverATask() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n [ ] \n[X] \n [ ] child")

        assertEquals("two separators, one child", listOf("child"), doc.topics.single().children.map { it.title })
        assertEquals(2, doc.stats.separatorLines)
        assertEquals(1, doc.stats.topics)
        assertEquals(1, doc.stats.tasks)
    }

    @Test
    fun blankPhysicalLinesAreSkippedButCountedApartFromSeparators() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n\n\n [ ] child\n [ ] ")

        assertEquals(2, doc.stats.blankLines)
        assertEquals(1, doc.stats.separatorLines)
        assertEquals("the two kinds of emptiness are not the same number", 3, doc.stats.blankLines + doc.stats.separatorLines)
    }

    // ---------------------------------------------------------------- indentation

    @Test
    fun theIndentUnitIsWhatTheFileItselfUsesRatherThanAGuess() {
        val oneSpace = KeepTextParser.parse("[ ] a\n [ ] b\n  [ ] c")
        assertEquals("one space per level in this file", listOf(0, 1, 2), oneSpace.allNodes().map { it.depth })

        val twoSpaces = KeepTextParser.parse("[ ] a\n  [ ] b\n    [ ] c")
        assertEquals("the same shape with a two-space unit still reads as three levels", listOf(0, 1, 2), twoSpaces.allNodes().map { it.depth })
    }

    @Test
    fun tabsAreAnIndentUnitOfTheirOwn() {
        val doc = KeepTextParser.parse("[ ] a\n\t[ ] b\n\t\t[ ] c")

        assertEquals(listOf(0, 1, 2), doc.allNodes().map { it.depth })
        assertEquals(0, doc.issues.size)
    }

    @Test
    fun aJumpOverAMissingLevelIsReportedAndAttachedToTheDeepestRealParent() {
        // Levels one and two exist, so a line at level five cannot be quietly made a child of a level that was
        // never written. The unit has to be established by a real level first: a file whose only indented line
        // sits at four spaces has no evidence in it about what a level is, and the parser reads that as one.
        val doc = KeepTextParser.parse("[ ] topic\n [ ] branch\n  [ ] leaf\n      [ ] five spaces deep")

        val leaf = doc.allNodes().first { it.title == "leaf" }
        val deep = doc.allNodes().last()
        assertEquals("attached to the deepest parent that actually exists", leaf.line, deep.line - 1)
        assertEquals(1, deep.depth - leaf.depth)
        assertEquals(
            "and the reader is told which line jumped, and what it was attached to",
            listOf(KeepParserIssue.IndentSkipped(line = 4, depth = 6, attachedToDepth = 3)),
            doc.issues
        )
    }

    @Test
    fun aFifthLevelIsReportedAndClampedInsteadOfVanishing() {
        val doc = KeepTextParser.parse("[ ] a\n [ ] b\n  [ ] c\n   [ ] d\n    [ ] too deep")

        assertEquals("nothing is dropped", 5, doc.allNodes().size)
        assertEquals("the contract stops at four levels", 3, doc.allNodes().maxOf { it.depth })
        assertEquals(1, doc.issues.count { it is KeepParserIssue.TooDeep })
    }

    // ---------------------------------------------------------------- lines the syntax does not cover

    @Test
    fun aLineWithoutACheckboxIsKeptWithAnUnknownStateAndReported() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n a plain note under it")

        val child = doc.topics.single().children.single()
        assertEquals("a plain note under it", child.title)
        assertEquals("the state is unknown, not assumed", KeepCheck.UNKNOWN, child.check)
        assertEquals(1, doc.stats.unknownStatus)
        assertEquals(
            "and the reader is told which line it was",
            listOf(2),
            doc.issues.filterIsInstance<KeepParserIssue.NoCheckbox>().map { it.line }
        )
    }

    @Test
    fun anUnknownStateIsNeverCountedAsCompleted() {
        val doc = KeepTextParser.parse("no box at all\n[X] a real one")

        assertEquals("only the real box counts as completed", 1, doc.stats.checked)
        assertEquals(1, doc.stats.unknownStatus)
        assertEquals(
            "and the unknown one is neither completed nor open",
            listOf(KeepCheck.UNKNOWN, KeepCheck.CHECKED),
            doc.allNodes().map { it.check }
        )
    }

    // ---------------------------------------------------------------- line endings and encoding

    @Test
    fun crlfLfAndABomAllProduceTheSameTree() {
        val expected = KeepTextParser.parse("[ ] a\n [ ] b").allNodes().map { it.title to it.depth }
        assertTrue("the reference tree has to exist before three spellings can match it", expected.size == 2)

        assertEquals(expected, KeepTextParser.parse("[ ] a\r\n [ ] b\r\n").allNodes().map { it.title to it.depth })
        assertEquals(expected, KeepTextParser.parse("\r[ ] a\r [ ] b").allNodes().map { it.title to it.depth })
        assertEquals(expected, KeepTextParser.parse("\uFEFF[ ] a\n [ ] b").allNodes().map { it.title to it.depth })
    }

    @Test
    fun titlesKeepCyrillicEmojiDigitsTimesAndPunctuationExactlyAsWritten() {
        val title = "🟥Сегодня — 26.12.26 - 11:00 - Ул. Петровка, д. 5"
        val doc = KeepTextParser.parse("[X] $title")

        assertEquals("not one character of it is normalised", title, doc.topics.single().title)
        assertEquals(KeepCheck.CHECKED, doc.topics.single().check)
    }

    @Test
    fun aDateInsideATitleStaysTextAndTheParserHasNowhereToPutADueDate() {
        val doc = KeepTextParser.parse("[ ] 26.12.26 - 11:00 - meet someone")

        assertEquals("26.12.26 - 11:00 - meet someone", doc.topics.single().title)
        // §8 forbids inventing metadata. A parser with no field to invent it into cannot produce it either,
        // so the contract is checked on the shape of the type rather than on one example's output.
        assertEquals(
            "a node is a title, a state, a depth, a line number and its children — nothing else",
            listOf("check", "children", "depth", "line", "title"),
            KeepNode::class.java.declaredFields.map { it.name }.sorted()
        )
    }

    @Test
    fun whitespaceAroundATitleIsNotPartOfIt() {
        // The real file ends several titles with a space before the CR; that space is not part of the name, and
        // the two spaces after a box are the separator between the box and the text, not an indent.
        val doc = KeepTextParser.parse("[ ] padded title \n[X]  double leading")

        assertEquals(2, doc.topics.size)
        assertEquals("padded title", doc.topics[0].title)
        assertEquals("double leading", doc.topics[1].title)
    }

    // ---------------------------------------------------------------- order, duplicates, nothing lost

    @Test
    fun duplicateTitlesStaySeparateNodes() {
        val doc = KeepTextParser.parse("[ ] $topicAlpha\n [ ] same child\n [ ] same child\n [ ] same child")

        assertEquals("three source lines, three nodes", 3, doc.topics.single().children.size)
        assertEquals(2, doc.stats.duplicateTitles)
    }

    @Test
    fun theOrderOfTheSourceIsTheOrderOfTheTree() {
        val doc = KeepTextParser.parse("[ ] one\n [ ] two\n  [ ] three\n [ ] four\n[ ] five")

        assertEquals(listOf("one", "two", "three", "four", "five"), doc.allNodes().map { it.title })
        assertEquals("line numbers are the source's own", listOf(1, 2, 3, 4, 5), doc.allNodes().map { it.line })
    }

    @Test
    fun nothingInTheInputIsDroppedOrCountedTwice() {
        val source = "[ ] a\n [ ] b\n [ ] \n\n [ ] c\n  [ ] d\n plain line\n[X] e"
        val doc = KeepTextParser.parse(source)

        val titled = source.lines().count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !(Regex("^[ \\t]*\\[[ xX]\\][ \\t]*$").matches(trimmed))
        }
        assertEquals("every line that carries something ends up in the tree", titled, doc.allNodes().size)
        assertEquals(doc.allNodes().size, doc.stats.topics + doc.stats.tasks)
    }

    @Test
    fun anEmptyInputIsAnEmptyDocumentRatherThanAnError() {
        val doc = KeepTextParser.parse("")

        assertEquals(0, doc.topics.size)
        assertEquals(0, doc.stats.topics + doc.stats.tasks)
        assertEquals(0, doc.issues.size)
    }

    @Test
    fun aFirstIndentedLineHasNoTopicToBelongToAndSaysSo() {
        val doc = KeepTextParser.parse(" [ ] an orphan before any topic")

        assertEquals(1, doc.issues.count { it is KeepParserIssue.NoParent })
        assertEquals("still not lost", 1, doc.allNodes().size)
    }
}
