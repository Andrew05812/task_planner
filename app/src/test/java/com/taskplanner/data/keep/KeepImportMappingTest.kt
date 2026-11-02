package com.taskplanner.data.keep

import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a parsed Keep tree turns into, and what it must never turn into.
 *
 * Three properties carry this stage, and each has a test that would fail if somebody "simplified" the mapping:
 *
 *  * **Nothing is invented.** No due date, no priority, no category, no reminder, no completion timestamp. The
 *    source says a thing is done and says nothing about when, so `completedAt` stays null and the status is what
 *    the box said.
 *  * **Identity is derived, not generated.** A uuid comes from the profile, the identity of the row above it,
 *    its title and which copy of that title it is — so pasting the same text twice writes the same rows, the
 *    second paste adds nothing, and a paste with one line edited adds that line alone. A random uuid per row
 *    would make every paste a duplicate import, and the paste's own fingerprint in the key would make a typo
 *    re-import the notebook.
 *  * **A name that already exists is never overwritten.** `GroupDao.upsertGroup` is an `OnConflictStrategy.REPLACE`
 *    insert, which for a topic means "delete the reader's row and put ours where it was". The mapping therefore
 *    reports a collision and refuses, and the importer never reaches for an upsert.
 */
class KeepImportMappingTest {

    private val userId = 7L

    private fun doc(source: String) = KeepTextParser.parse(source)

    /**
     * The existing rows arrive as uuid to owner-id rather than as a set of uuids, because a set cannot tell
     * the two cases apart: the same uuid already there because this profile pasted this text before (skip it)
     * and the same uuid already there because another profile owns that row (refuse the import).
     */
    private fun plan(
        source: String,
        groups: Map<String, Long> = emptyMap(),
        tasks: Map<String, Long> = emptyMap()
    ) = KeepImportMapping.plan(
        document = doc(source),
        userId = userId,
        existingGroups = groups,
        existingTasks = tasks
    )

    private val small = "[X] Finished topic\n [ ] open child\n [X] done child\n  [ ] grandchild"

    // ---------------------------------------------------------------- the mapping itself

    @Test
    fun aTopicBecomesAGroupAndItsLinesBecomeTasksOfIt() {
        val result = plan(small)

        assertEquals(1, result.groups.size)
        assertEquals("Finished topic", result.groups.single().name)
        assertEquals(userId, result.groups.single().userId)
        assertEquals(3, result.tasks.size)
        assertTrue(
            "every task of a topic carries the topic it belongs to",
            result.tasks.all { it.groupId == result.groups.single().uuid }
        )
        assertTrue("and none of them carries a category, a reminder or a date", result.tasks.all {
            it.categoryId == null && it.dueDate == null && it.startTime == null && it.endTime == null &&
                it.seriesUuid == null && it.estimatedMinutes == null
        })
    }

    @Test
    fun theTreeShapeTravelsIntoParentUuids() {
        val result = plan(small)
        val byTitle = result.tasks.associateBy { it.title }

        assertEquals("an indented line under a topic has no task parent", null, byTitle.getValue("open child").parentUuid)
        assertEquals(
            "a line indented under a task names the nearest task above it — here the `[X] done child`, not the " +
                "sibling before it",
            byTitle.getValue("done child").uuid,
            byTitle.getValue("grandchild").parentUuid
        )
        assertEquals("and it belongs to the same topic either way", result.groups.single().uuid,
            byTitle.getValue("grandchild").groupId)
    }

    @Test
    fun completionComesFromTheBoxAndNothingElse() {
        val result = plan(small)
        val byTitle = result.tasks.associateBy { it.title }

        assertEquals(TaskStatus.COMPLETED, byTitle.getValue("done child").status)
        assertEquals(TaskStatus.TODO, byTitle.getValue("open child").status)
        assertEquals(TaskStatus.TODO, byTitle.getValue("grandchild").status)
        assertEquals("a completed task gets no invented timestamp", null, byTitle.getValue("done child").completedAt)
        assertTrue("the topic keeps its own state", result.groups.single().isCompleted)
    }

    @Test
    fun aTopicCompletedInSourceDoesNotCompleteWhatHangsUnderIt() {
        val result = plan("[X] parent\n [ ] child still open")

        assertTrue(result.groups.single().isCompleted)
        assertEquals(TaskStatus.TODO, result.tasks.single().status)
    }

    @Test
    fun orderIsTheSourceOrderWithinEachParent() {
        val result = plan("[ ] t\n [ ] a\n [ ] b\n [ ] c")
        val orders = result.tasks.sortedBy { it.order }.map { it.title }

        assertEquals(listOf("a", "b", "c"), orders)
        assertEquals("siblings number from zero, in the order the file wrote them", listOf(0, 1, 2), result.tasks.map { it.order })
    }

    @Test
    fun titlesAreCopiedCharacterForCharacter() {
        val source = "[X] 🟥Сегодня — 12.03.27 - 09:30 - Ул. Примера, д. 7"
        val result = plan(source)

        assertEquals("🟥Сегодня — 12.03.27 - 09:30 - Ул. Примера, д. 7", result.groups.single().name)
    }

    @Test
    fun twoLinesWithTheSameNameBecomeTwoRows() {
        val result = plan("[ ] t\n [ ] buy milk\n [ ] buy milk")

        assertEquals(2, result.tasks.size)
        assertNotEquals("identical titles are still two different rows", result.tasks[0].uuid, result.tasks[1].uuid)
        assertEquals("and they differ only by which occurrence they are", 1, result.tasks[1].order)
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun theSameTextTwiceProducesTheSameRows() {
        val first = plan(small)
        val second = plan(small)

        assertEquals(
            "identity is derived from the source, so a second paste is the same set of uuids",
            first.groups.map { it.uuid } + first.tasks.map { it.uuid },
            second.groups.map { it.uuid } + second.tasks.map { it.uuid }
        )
    }

    @Test
    fun aDifferentProfileGetsDifferentRowsForTheSameText() {
        val mine = KeepImportMapping.plan(doc(small), 7L, emptyMap(), emptyMap())
        val theirs = KeepImportMapping.plan(doc(small), 8L, emptyMap(), emptyMap())

        assertNotEquals(mine.groups.single().uuid, theirs.groups.single().uuid)
    }

    @Test
    fun editedTextProducesDifferentRowsOnlyWhereItChanged() {
        val before = plan("[ ] t\n [ ] one\n [ ] two")
        val after = plan("[ ] t\n [ ] one\n [ ] two changed")

        assertEquals("the untouched line keeps its identity", before.tasks[0].uuid, after.tasks[0].uuid)
        assertNotEquals("the edited line does not", before.tasks[1].uuid, after.tasks[1].uuid)
    }

    @Test
    fun aFingerprintIsStableAndTextSensitive() {
        assertEquals(KeepImportIdentity.fingerprintOf("a\r\nb"), KeepImportIdentity.fingerprintOf("a\nb"))
        assertNotEquals(KeepImportIdentity.fingerprintOf("a\nb"), KeepImportIdentity.fingerprintOf("a\nB"))
    }

    @Test
    fun aLineInsertedAboveItsSiblingsDoesNotReIdentifyThem() {
        // Position is deliberately not part of a row's identity, and this is the case that pays for it: the reader
        // adds one line to the top of a topic in Keep and pastes again. Only that line becomes a row.
        val before = plan("[ ] topic\n [ ] one\n [ ] two")
        val after = plan("[ ] topic\n [ ] brand new\n [ ] one\n [ ] two")

        assertEquals(2, before.tasks.size)
        assertEquals(3, after.tasks.size)
        assertEquals(
            "the two untouched lines are the same rows even though they both moved down",
            before.tasks.map { it.uuid },
            after.tasks.takeLast(2).map { it.uuid }
        )
    }

    @Test
    fun theSameTitleUnderTwoDifferentTopicsIsTwoRows() {
        val result = plan("[ ] Alpha\n [ ] buy milk\n[ ] Beta\n [ ] buy milk")

        assertEquals(2, result.groups.size)
        assertEquals(2, result.tasks.size)
        assertNotEquals("same words, different parents, different identities",
            result.tasks[0].uuid, result.tasks[1].uuid)
        assertNotEquals(result.tasks[0].groupId, result.tasks[1].groupId)
    }

    @Test
    fun aFingerprintNamesThePasteWithoutEnteringARowIdentity() {
        // The paste digest is what the screen reports and a log could quote; it is not what a row is keyed on. Were
        // it in the key, these two texts would share nothing and an edited paste would duplicate all six rows.
        val shorter = plan("[ ] topic\n [ ] one")
        val longer = plan("[ ] topic\n [ ] one\n [ ] two")
        assertNotEquals(KeepImportIdentity.fingerprintOf("[ ] topic\n [ ] one"),
            KeepImportIdentity.fingerprintOf("[ ] topic\n [ ] one\n [ ] two"))
        assertEquals("the unchanged line keeps its identity even though the paste changed",
            shorter.tasks.single().uuid, longer.tasks.first().uuid)
    }

    // ---------------------------------------------------------------- collisions and idempotency

    @Test
    fun aRowThatAlreadyExistsFromTheSamePasteIsSkippedRatherThanDuplicated() {
        val planned = plan(small)
        val second = KeepImportMapping.plan(
            document = doc(small),
            userId = userId,
            existingGroups = planned.groups.associate { it.uuid to userId },
            existingTasks = planned.tasks.associate { it.uuid to userId }
        )

        assertEquals("nothing left to insert", 0, second.groups.size)
        assertEquals(0, second.tasks.size)
        assertTrue(second.conflicts.isEmpty())
        assertTrue("and the plan says so, so the preview can tell the reader why nothing happened", second.alreadyImported)
    }

    @Test
    fun aUuidAlreadyOwnedByAnotherProfileIsAConflictAndNothingIsWritten() {
        val hijacked = plan(small).tasks.first().uuid
        val result = plan(small, tasks = mapOf(hijacked to 999L))

        assertEquals(1, result.conflicts.size)
        assertEquals(hijacked, result.conflicts.single().uuid)
        assertTrue("a plan with a conflict writes no rows at all", result.groups.isEmpty() && result.tasks.isEmpty())
    }

    @Test
    fun aGroupCollisionIsReportedTheSameWay() {
        val hijacked = plan(small).groups.first().uuid
        val result = plan(small, groups = mapOf(hijacked to 999L))

        assertEquals(1, result.conflicts.size)
        assertFalse(result.groups.any { it.uuid == hijacked })
    }

    @Test
    fun thePlanCarriesTheCountsThePreviewShows() {
        val result = plan(small)

        assertEquals(1, result.stats.topics)
        assertEquals(3, result.stats.tasks)
        assertEquals("the plan's own totals have to agree with the rows it produced", result.tasks.size, result.stats.tasks)
    }

    @Test
    fun aTaskRowIsBuiltForTheUserThatAsked() {
        val result = plan(small)

        assertTrue(result.tasks.all { it.userId == userId })
        assertTrue(result.groups.all { it.userId == userId })
        assertTrue("and every row starts unsynced, the way a locally created one does", result.tasks.all { it.syncState.name == "PENDING" })
    }

    // ---------------------------------------------------------------- what the source does not say

    @Test
    fun aLineWithNoCheckboxBecomesAnOpenTaskAndIsCountedAsUncertain() {
        val result = plan("[ ] t\n the reader wrote this without a box")

        assertEquals(1, result.tasks.size)
        assertEquals(TaskStatus.TODO, result.tasks.single().status)
        assertEquals("the preview has to say which rows it had to guess about", 1, result.stats.unknownStatus)
    }

    @Test
    fun theFirstRowOfEveryTopicIsNotNestedAndTheOnesBelowItAre() {
        val result = plan("[ ] a\n [ ] child of a\n  [ ] grandchild of a\n[ ] b\n [ ] child of b")

        assertEquals(2, result.groups.size)
        assertEquals(5, result.stats.topics + result.stats.tasks)
        assertEquals("only the line indented under another task is nested — a topic's direct child is not",
            1, result.stats.nestedTasks)
        val byTitle = result.tasks.associateBy { it.title }
        assertEquals("a child of a topic belongs to the topic, not to a task", null, byTitle.getValue("child of a").parentUuid)
        assertEquals(byTitle.getValue("child of a").uuid, byTitle.getValue("grandchild of a").parentUuid)
        assertEquals("and the whole subtree still hangs from one topic",
            result.groups[0].uuid, byTitle.getValue("grandchild of a").groupId)
        assertEquals(result.groups[1].uuid, byTitle.getValue("child of b").groupId)
    }

    @Test
    fun aTreeDeeperThanTheContractRefusesRatherThanFlattensItself() {
        // The parser clamps at four levels and reports it, so this document cannot come from KeepTextParser. It is
        // built here to prove the mapping's own tripwire: a tree that breaks the contract stops the import instead
        // of quietly writing a row at a depth nothing downstream knows how to draw.
        val tooDeep = KeepNode(
            title = "topic", check = KeepCheck.UNCHECKED, depth = 0, line = 1,
            children = listOf(
                KeepNode("l1", KeepCheck.UNCHECKED, 1, 2, listOf(
                    KeepNode("l2", KeepCheck.UNCHECKED, 2, 3, listOf(
                        KeepNode("l3", KeepCheck.UNCHECKED, 3, 4, listOf(
                            KeepNode("l4", KeepCheck.UNCHECKED, 4, 5, emptyList())
                        ))
                    ))
                ))
            )
        )
        val document = KeepParsedDocument(listOf(tooDeep), doc("[ ] topic").stats, emptyList())

        val failure = try {
            KeepImportMapping.plan(document, userId, emptyMap(), emptyMap())
            error("expected the mapping to refuse a fifth level")
        } catch (expected: IllegalStateException) {
            expected.message.orEmpty()
        }

        assertTrue("the message points at the line: $failure", failure.contains("line 5"))
        assertFalse("and it does not repeat the reader's text", failure.contains("l4"))
    }

    /**
     * The two figures the preview prints beside its tree belong to the plan, not to the list on screen.
     *
     * Found on the device, in a frame: with one topic folded, «rows to write» read 5 where the same paste had
     * said 8, because the header was counting the rows the list was drawing. Folding a branch is a way of looking;
     * it is not a smaller import, and a figure that moves when the reader scrolls is the kind of promise §11 asks
     * the preview not to make. The plan is the only thing in the chain that knows both halves — what the paste
     * named and what this profile already owns — so the counts live there, and the screen has one call to make.
     */
    @Test
    fun thePlanCountsEveryRowItHoldsWhateverTheScreenHasFolded() {
        val result = plan("[ ] a\n [ ] one\n  [ ] deep\n[ ] b\n [ ] two")
        val opened = KeepPreviewRows.of(result, result.candidates.filter { it.isTopic }.map { it.key }.toSet())
        val folded = KeepPreviewRows.of(result, emptySet())

        assertEquals("two topics and three tasks, all of them to write", 5, result.rowsToWrite)
        assertEquals("nothing of it is already here", 0, result.rowsAlreadyHere)
        assertEquals("and the three figures the screen prints add up to the paste it came from",
            result.candidates.size, result.rowsToWrite + result.rowsAlreadyHere + result.conflicts.size)

        assertEquals("folding both topics takes rows off the *list* — that is what folding is",
            2, folded.size)
        assertEquals("and the drawn list is the thing that shrank, not the plan",
            2, folded.count { it.status == KeepRowStatus.WILL_WRITE })
        assertEquals("while every row of the opened list was headed for the database",
            5, opened.count { it.status == KeepRowStatus.WILL_WRITE })
    }

    /**
     * The shape figures describe the paste; the write figures describe this profile.
     *
     * Found in the same device frame as the count above: a re-paste of text the profile already owns writes
     * nothing, and the preview's «nested under a task» figure — read from the plan's write statistics — went to 0
     * beside a tree that still showed its grandchild indented. The paste did not stop nesting because the import
     * had nothing to do.
     */
    @Test
    fun theShapeOfThePasteIsStatedWhateverTheProfileAlreadyOwns() {
        val fresh = plan(small)
        val owned = plan(
            small,
            groups = fresh.candidates.filter { it.isTopic }.associate { it.uuid to userId },
            tasks = fresh.candidates.filterNot { it.isTopic }.associate { it.uuid to userId }
        )

        assertEquals("a re-paste writes nothing", 0, owned.rowsToWrite)
        assertEquals("and every line of it is already here", 4, owned.rowsAlreadyHere)
        assertEquals("while the paste nests one line under a task, written or not",
            fresh.nestedRows, owned.nestedRows)
        assertEquals(1, owned.nestedRows)
    }

    @Test
    fun theSortPositionsStartWhereTheReaderSListAlreadyEnds() {
        val result = KeepImportMapping.plan(
            document = doc("[ ] a\n [ ] one\n[ ] b\n [ ] two"),
            userId = userId,
            existingGroups = emptyMap(),
            existingTasks = emptyMap(),
            firstGroupSortOrder = 12,
            firstTaskOrder = 40
        )

        assertEquals(listOf(12, 13), result.groups.map { it.sortOrder })
        assertEquals(listOf(40, 41), result.tasks.map { it.order })
    }
}
