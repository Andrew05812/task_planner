package com.taskplanner.data.hierarchy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy.MoveDestination
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy.TreeFacts
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §6.3: the list of places a "Move to" sheet may put a task's branch, and the one rule that decides it.
 *
 * Stage 16's goal is that a reader can edit a tree they already have, and the sheet is how they will do it. The brief
 * is exact about two things: the sheet must be built from one domain answer («добавь единый метод policy для построения
 * этого списка», reading `factsOf`, `subtreeOf`, `ambiguousUuids`, `fitsUnder` and the caller's user/group snapshot),
 * and it must not show an inadmissible variant as clickable («не показывай недопустимые варианты как кликабельные»).
 * This file holds no sheet — it holds the answer the next task renders, and earns the two properties that make the
 * copy honest:
 *
 *  * **Every destination the policy offers is one the repository writes.** A row the reader can press and the domain
 *    then refuses is a control that does not work, and the reader has learned that about the app before.
 *  * **Everything the repository refuses is absent from the list.** Not greyed out — absent. The exclusions §3 names
 *    are the moved row itself, its descendants, another profile's rows, a topic the notebook does not hold, a parent
 *    with no identity or with two, and a move past the third level.
 *
 * Both hold *by construction* rather than by two copies agreeing: [TaskHierarchyRepository.moveSubtreeUnder] now
 * consults [TaskHierarchyPolicy.moveRefusal], the same function [TaskHierarchyPolicy.destinationsFor] filters with —
 * exactly as §6.1 moved indent and outdent out of that transaction. `the extraction did not change what the repository
 * decides` holds that extraction to §5's pinned answers, and `HierarchyMoveTest`, unedited in this same directory, is
 * the older half of the same proof.
 *
 * One row of the list carries what the sheet has to *say*: the topic object (never a name alone, so two topics holding
 * rows of the same title stay tellable apart), the parent row, the level the head lands at, how many tasks travel with
 * the branch, and the path of titles down to it.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyDestinationTest {

    private lateinit var fixture: AppFixture
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    /**
     * Topics are listed by `sort_order`, and the fixture gives each one its own.
     *
     * Left at the default every topic shares, two topics tie and SQLite settles it by rowid — so a test about the
     * order a sheet draws its rows in would be asserting whatever the query planner chose that day.
     */
    private var nextSortOrder = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        userId = runBlocking { fixture.signIn(email = "destinations@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
    }

    @After
    fun tearDown() = fixture.close()

    /** A topic written by this profile, with its uuid left explicit so a destination can be named in the assert. */
    private fun group(name: String): TaskGroup = runBlocking {
        val group = TaskGroup(
            uuid = name, userId = userId, name = name, sortOrder = nextSortOrder++,
            syncState = SyncState.SYNCED, createdAt = STAMP, updatedAt = STAMP
        )
        fixture.groupDao.upsertGroup(group)
        fixture.groupDao.getGroupByUuid(name)!!
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        group: String? = null,
        uuid: String = title,
        owner: Long = userId
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = owner, groupId = group,
                parentUuid = parent, order = position, syncState = SyncState.SYNCED,
                createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    /** The profile's rows in the one sequence a move reads and writes — the same read the transaction makes. */
    private fun stored(): List<Task> =
        TaskHierarchyPolicy.canonicalSequence(runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) })

    private fun facts(): TreeFacts = TaskHierarchyPolicy.factsOf(stored())

    /** The caller's topic snapshot, live topics only: exactly what `ownsTopic` answers `true` for. */
    private fun topics(): List<TaskGroup> = runBlocking { fixture.groupDao.getGroupsOnce(userId) }

    private fun ownedTopicUuids(): Set<String> = topics().map { it.uuid }.toSet()

    private fun destinations(
        head: Task,
        topics: List<TaskGroup>,
        owned: Set<String>,
        facts: TreeFacts
    ): List<MoveDestination> = TaskHierarchyPolicy.destinationsFor(head, topics, owned, facts)

    private fun destinations(head: Task): List<MoveDestination> =
        destinations(head, topics(), ownedTopicUuids(), facts())

    /**
     * How a sheet would identify one row of its list: which topic, and which task it hangs the head on.
     *
     * The id rather than the row, because two calls that read the same storage hand back equal-but-distinct [Task]s,
     * and a set built from the rows would call one place in the tree two different destinations.
     */
    private fun keyOf(destination: MoveDestination): Pair<String?, Long?> =
        destination.topic?.uuid to destination.parent?.id

    private fun offeredParents(list: List<MoveDestination>): Set<Long> =
        list.mapNotNull { it.parent?.id }.toSet()

    private fun refusedByDomain(reason: HierarchyMoveRefusal, call: suspend () -> HierarchyMoveResult) {
        val outcome = runBlocking { call() }
        assertEquals("the domain answered: $outcome", reason, (outcome as HierarchyMoveResult.Refused).reason)
    }

    private fun accepted(outcome: HierarchyMoveResult): HierarchyMoveResult.Accepted {
        assertTrue("the domain should have written this move, and it answered: $outcome",
            outcome is HierarchyMoveResult.Accepted)
        return outcome as HierarchyMoveResult.Accepted
    }

    /** Everything a move could damage, in one comparable value: link, topic, position, push state. */
    private fun shape(): List<List<Any?>> = stored().map {
        listOf(it.uuid, it.parentUuid, it.groupId, it.order, it.syncState)
    }

    /**
     * Runs [body] against real storage, then puts the profile back exactly as the list was built from it.
     *
     * An accepted move rewrites parent links, topics and positions, so a loop over a destination list would otherwise
     * measure the second row against the tree the first press left behind — and a later destination would then be
     * refused for a reason the list never predicted, which is a bug in the test rather than in the rule. The rows come
     * back through the same DAO that wrote them, so every case in the loop starts from this fixture.
     */
    private fun <T> withRestoredProfile(body: () -> T): T {
        val seed = stored()
        val answer = body()
        runBlocking { fixture.taskDao.updateTasks(seed) }
        assertEquals("the restore put the tree back where the list found it", seed, stored())
        return answer
    }

    // ── the two halves of one rule ──────────────────────────────────────────────────────────────────────────

    /**
     * Pressing a row of the sheet is a write the domain takes.
     *
     * The head carries a child, so this is the block that runs out of depth two levels up as well: three roots, three
     * admissible parents, and exactly one row left out for the depth — `loose`, already at the second level, where the
     * branch's own grandchild would land on a fourth.
     */
    @Test
    fun `every destination the policy offers is accepted by the repository`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        val head = row("draft", position = 1, group = office.uuid)
        row("figures", parent = "draft", position = 2, group = office.uuid)
        row("notes", position = 3, group = errand.uuid)
        row("receipts", position = 4)
        row("loose", parent = "receipts", position = 5)
        val before = shape()

        val list = destinations(head)
        assertEquals("one root per topic, in the snapshot's order, and the standalone tree last",
            listOf(office.uuid, errand.uuid, null),
            list.filter { it.parent == null }.map { it.topic?.uuid }
        )
        assertEquals("three roots and three parents, with `loose` refused by the depth bound",
            6, list.size)
        assertEquals("and the row the head would land under its own child is not among them",
            false, list.any { it.parent?.uuid == "loose" })

        list.forEach { destination ->
            val outcome = withRestoredProfile {
                runBlocking {
                    hierarchy.moveSubtreeUnder(head.id, destination.parent?.id, destination.topic?.uuid)
                }
            }
            accepted(outcome)
        }
        assertEquals("eight presses' worth of writes, and the tree is the one the list was built from",
            before, shape())
    }

    /**
     * The mirror of the case above, on one fixture holding a single refused row per exclusion.
     *
     * Each refused row is named by running the repository against it first, so the exclusion below is a refusal the
     * domain actually makes rather than a row this file happens to have left out.
     */
    @Test
    fun `a destination the repository refuses is never offered`() {
        val office = group("office-block")
        val planning = row("planning", position = 0, group = office.uuid)
        val deep = row("draft", parent = "planning", position = 1, group = office.uuid)
        val twin = row("figures", position = 2, group = office.uuid, uuid = "twin")
        row("receipts", position = 3, group = office.uuid, uuid = "twin")
        val nameless = row("nameless", uuid = "", position = 4, group = office.uuid)
        val head = row("notes", position = 5, group = office.uuid)
        val ownChild = row("carried", parent = "notes", position = 6, group = office.uuid)
        val before = shape()

        refusedByDomain(HierarchyMoveRefusal.DEPTH_EXCEEDED) {
            hierarchy.moveSubtreeUnder(head.id, deep.id, office.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.AMBIGUOUS_PARENT) {
            hierarchy.moveSubtreeUnder(head.id, twin.id, office.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.PARENT_UNNAMED) {
            hierarchy.moveSubtreeUnder(head.id, nameless.id, office.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE) {
            hierarchy.moveSubtreeUnder(head.id, ownChild.id, office.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.SAME_ROW) {
            hierarchy.moveSubtreeUnder(head.id, head.id, office.uuid)
        }
        assertEquals("five refusals, no write", before, shape())

        val refused = setOf(deep.id, twin.id, nameless.id, ownChild.id, head.id)
        val list = destinations(head)
        assertEquals("not one of them is a row the sheet would draw",
            emptySet<Long>(), offeredParents(list).intersect(refused))
        assertTrue("while the row the domain does take is still on the list: $list",
            offeredParents(list).contains(planning.id))
    }

    // ── the exclusions §3 names ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the moved row itself is not a destination`() {
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        val sibling = row("draft", position = 1, group = office.uuid)
        row("notes", position = 2)

        val list = destinations(head)
        assertEquals("hanging a row on itself is what the write refuses as SAME_ROW, so it is not drawn",
            false, list.any { it.parent?.id == head.id })
        refusedByDomain(HierarchyMoveRefusal.SAME_ROW) {
            hierarchy.moveSubtreeUnder(head.id, head.id, office.uuid)
        }
        assertTrue("and the list is not empty for a reason a reader could not check: $list",
            offeredParents(list).contains(sibling.id))
    }

    @Test
    fun `a descendant is not a destination at any depth`() {
        val office = group("office-block")
        val errand = group("errand-block")
        val head = row("planning", position = 0, group = office.uuid)
        val child = row("draft", parent = "planning", position = 1, group = office.uuid)
        val grandchild = row("figures", parent = "draft", position = 2, group = office.uuid)
        // Deeper than the contract draws, and deeper than the bounded walk in `hangsUnder` can reach: the block is
        // named by ids rather than by a walk, so a row four levels down is refused as the loop it is, not measured.
        val beyondTheWalk = row("receipts", parent = "figures", position = 3, group = office.uuid)
        row("notes", position = 4, group = errand.uuid)

        val list = destinations(head)
        listOf(child, grandchild, beyondTheWalk).forEach { descendant ->
            assertEquals("${descendant.uuid} is inside the branch being moved, so it is not offered",
                false, list.any { it.parent?.id == descendant.id })
            refusedByDomain(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE) {
                hierarchy.moveSubtreeUnder(head.id, descendant.id, office.uuid)
            }
        }
        // The positive control is a root rather than a row, because this branch is four levels deep already: every
        // parent destination is out of reach for it on depth alone, which would let a loop that dropped all four
        // descendants pass on an empty list. A topic taking the block as its own child is what is still on offer.
        assertEquals("the block can still be filed under a topic — that half is not what the loop refuses",
            listOf(office.uuid, errand.uuid, null), list.filter { it.parent == null }.map { it.topic?.uuid })
    }

    @Test
    fun `a parent outside the profile is not offered and is refused when forced`() {
        val stranger = runBlocking { fixture.signIn(name = "Stranger", email = "stranger@example.com").id }
        val foreignTopic = TaskGroup(uuid = "their-topic", userId = stranger, name = "their-topic")
        runBlocking { fixture.groupDao.upsertGroup(foreignTopic) }
        val foreignRow = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "theirs", description = "", uuid = "theirs", userId = stranger, order = 0)
            )
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        row("draft", position = 1)

        // (1) A row of another profile: absent from the caller's snapshot, so never a candidate — and refused by the
        // repository's own lookup when a stale sheet names it by id.
        refusedByDomain(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE) {
            hierarchy.moveSubtreeUnder(head.id, foreignRow, null)
        }
        assertEquals("a row this profile does not hold is not on the list at all",
            false, destinations(head).any { it.parent?.id == foreignRow })
        // (2) The same fact asked of the rule rather than of storage: a caller that hands in a row outside the facts
        // gets the answer the write would give, which is the half a sheet built from an older snapshot needs.
        assertEquals("the rule answers it too, from the facts alone",
            HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE,
            TaskHierarchyPolicy.moveRefusal(
                head = head,
                blockIds = setOf(head.id),
                blockHeight = 1,
                parent = runBlocking { fixture.taskDao.getTaskById(foreignRow) }!!,
                destinationGroupUuid = null,
                profileTopicUuids = ownedTopicUuids(),
                facts = facts()
            ))
        // (3) A topic of another profile named as the destination.
        refusedByDomain(HierarchyMoveRefusal.TOPIC_MISSING) {
            hierarchy.moveSubtreeUnder(head.id, null, foreignTopic.uuid)
        }
        assertEquals("and no destination wears its name",
            false, destinations(head).any { it.topic?.uuid == foreignTopic.uuid })
    }

    @Test
    fun `a topic the profile does not own offers nothing`() {
        val office = group("office-block")
        val doomed = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        val inDoomed = row("draft", position = 1, group = doomed.uuid)
        val head = row("notes", position = 2)
        runBlocking { fixture.groupDao.softDelete(doomed.uuid) }

        // A reader's own topic, deleted: `getGroupsOnce` no longer lists it and `ownsTopic` answers false for it, so
        // the snapshot a sheet is built from holds neither the uuid nor — for the caller that still lists the row — a
        // destination inside it. Requirement 3: a topic missing from the owned set produces nothing at all, and that
        // includes the rows still filed under it.
        val owned = ownedTopicUuids()
        assertEquals("the tombstone is not an owned destination", false, owned.contains(doomed.uuid))
        val list = destinations(head, topics = listOf(office, doomed), owned = owned, facts = facts())
        assertEquals("nothing is offered under a topic the notebook does not hold",
            emptyList<MoveDestination>(), list.filter { it.topic?.uuid == doomed.uuid })
        assertEquals("not even its rows, which are nowhere on the list",
            emptyList<MoveDestination>(), list.filter { it.parent?.id == inDoomed.id })
        assertTrue("while the live topic keeps both kinds of destination: $list",
            list.any { it.topic?.uuid == office.uuid && it.parent == null } &&
                list.any { it.topic?.uuid == office.uuid && it.parent != null })
        refusedByDomain(HierarchyMoveRefusal.TOPIC_MISSING) {
            hierarchy.moveSubtreeUnder(head.id, null, doomed.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.TOPIC_MISSING) {
            hierarchy.moveSubtreeUnder(head.id, inDoomed.id, doomed.uuid)
        }

        // A topic with no identity of its own: §4 records that a blank `group_id` files nothing and that a hand-edited
        // backup can leave one in `task_groups`, and the write normalises a blank destination to the standalone tree.
        // So a row of the sheet wearing such a group's name would promise a filing the transaction does not make, and
        // the destination list refuses it the way it refuses an unowned one — ownership aside, which is why the owned
        // set below is handed the blank uuid: the skip is about identity, not about who holds it.
        val nameless = TaskGroup(
            uuid = "", userId = userId, name = "nameless-topic", createdAt = STAMP, updatedAt = STAMP
        )
        runBlocking { fixture.groupDao.upsertGroup(nameless) }
        val withNameless = destinations(
            head,
            topics = listOf(office, doomed, nameless),
            owned = owned + nameless.uuid,
            facts = facts()
        )
        assertEquals("a topic that cannot own a row is not offered as a place to put one",
            emptyList<MoveDestination>(), withNameless.filter { it.topic?.uuid?.isBlank() == true })
        assertEquals("and the whole list is the list the same snapshot gives without it", list, withNameless)
        assertEquals("the standalone tree stays the one destination the write would make for such a request",
            1, withNameless.count { it.topic == null && it.parent == null })
    }

    @Test
    fun `a destination that would pass the third level is refused and excluded`() {
        val office = group("office-block")
        val first = row("planning", position = 0, group = office.uuid)
        val second = row("draft", parent = "planning", position = 1, group = office.uuid)
        val head = row("figures", position = 2, group = office.uuid)
        row("receipts", parent = "figures", position = 3, group = office.uuid)
        val before = shape()

        // The head carries a child, so its block is two rows tall: under `draft`, already at the second level, the
        // reader's grandchild would land on level four — a level the contract does not have and a card has no room
        // for. The shallower place in the same topic stays on the list.
        val list = destinations(head)
        assertEquals("the fourth level is not offered as a pressable row",
            false, list.any { it.parent?.id == second.id })
        assertTrue("and the second level still is: $list", offeredParents(list).contains(first.id))
        refusedByDomain(HierarchyMoveRefusal.DEPTH_EXCEEDED) {
            hierarchy.moveSubtreeUnder(head.id, second.id, office.uuid)
        }
        assertEquals("the refusal wrote nothing, and lost no row on the way", before, shape())
    }

    @Test
    fun `a parent whose identity is doubled is never offered`() {
        val office = group("office-block")
        val twinOne = row("planning", position = 0, group = office.uuid, uuid = "twin")
        val twinTwo = row("draft", position = 1, group = office.uuid, uuid = "twin")
        val single = row("figures", position = 2, group = office.uuid)
        val head = row("notes", position = 3, group = office.uuid)
        val before = shape()

        // Two live rows on one identity, so "the branch under it" has two answers and so does the level the depth rule
        // would measure. §6.1's rule for a notebook that inconsistent is that it is not offered as a press at all.
        val list = destinations(head)
        assertEquals("neither row claiming the doubled identity is a destination",
            emptySet<Long>(), offeredParents(list).intersect(setOf(twinOne.id, twinTwo.id)))
        assertTrue("while the row with one identity of its own still is, so the case above is an exclusion and not " +
            "an empty list: $list", offeredParents(list).contains(single.id))
        refusedByDomain(HierarchyMoveRefusal.AMBIGUOUS_PARENT) {
            hierarchy.moveSubtreeUnder(head.id, twinOne.id, office.uuid)
        }
        refusedByDomain(HierarchyMoveRefusal.AMBIGUOUS_PARENT) {
            hierarchy.moveSubtreeUnder(head.id, twinTwo.id, office.uuid)
        }
        assertEquals("and neither write left a link that could be read two ways", before, shape())
    }

    @Test
    fun `a parent with no identity is never offered`() {
        val office = group("office-block")
        val nameless = row("nameless", uuid = "", position = 0, group = office.uuid)
        val named = row("planning", position = 1, group = office.uuid)
        val head = row("notes", position = 2, group = office.uuid)

        // A blank uuid cannot take a child: writing one into `parent_uuid` reads back as "a topic's own child", which
        // is an un-parenting nobody asked for. The row is on screen all the same, so the sheet cannot show it as
        // pressable and have the write answer something else.
        val list = destinations(head)
        assertEquals("a row with no identity is not a destination",
            false, list.any { it.parent?.id == nameless.id })
        assertTrue("while its neighbour, which has one, is: $list", offeredParents(list).contains(named.id))
        refusedByDomain(HierarchyMoveRefusal.PARENT_UNNAMED) {
            hierarchy.moveSubtreeUnder(head.id, nameless.id, office.uuid)
        }
    }

    @Test
    fun `a parent from another topic is refused as a topic mismatch`() {
        val office = group("office-block")
        val errand = group("errand-block")
        val head = row("planning", position = 0, group = office.uuid)
        val inErrand = row("draft", position = 1, group = errand.uuid)
        val before = shape()

        refusedByDomain(HierarchyMoveRefusal.TOPIC_MISMATCH) {
            hierarchy.moveSubtreeUnder(head.id, inErrand.id, office.uuid)
        }
        assertEquals("one name or the other, never a tree split across both topics", before, shape())

        // The list cannot even express the mismatch: a destination carries the topic object its parent is filed in, so
        // the errand row appears once — under the errand block — and never as a place to put an office row.
        val list = destinations(head)
        assertEquals("the other topic's row is not offered inside this one",
            emptyList<MoveDestination>(),
            list.filter { it.topic?.uuid == office.uuid && it.parent?.id == inErrand.id })
        assertEquals("it appears exactly once, under the topic that holds it",
            1, list.count { it.parent?.id == inErrand.id })
        assertEquals(errand.uuid, list.first { it.parent?.id == inErrand.id }.topic?.uuid)
    }

    // ── what one row of the list carries ────────────────────────────────────────────────────────────────────

    @Test
    fun `each topic contributes its own root destination`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        row("draft", position = 1, group = errand.uuid)
        val head = row("notes", position = 2)

        val roots = destinations(head).filter { it.parent == null }
        assertEquals("one per topic, in the order the caller's snapshot lists them, and the standalone tree last",
            listOf(office.uuid, errand.uuid, null), roots.map { it.topic?.uuid })
        assertEquals("a topic's own child is the shallowest place the tree has",
            listOf(1, 1, 1), roots.map { it.resultingLevel })
        assertEquals("and its path is the topic's own name — or nothing, for the standalone tree",
            listOf(listOf("office-block"), listOf("errand-block"), emptyList()), roots.map { it.pathTitles })
        assertEquals("the topic travels as the object, so two of one title stay tellable apart",
            listOf(office, errand), roots.take(2).map { it.topic })
    }

    @Test
    fun `the standalone tree is a destination`() {
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        row("draft", position = 1)

        val list = destinations(head)
        val standalone = list.single { it.topic == null && it.parent == null }
        assertEquals("a task with no topic is a state this notebook holds, not a defect",
            MoveDestination(null, null, 1, 1, emptyList()), standalone)
        val outcome = withRestoredProfile {
            runBlocking { hierarchy.moveSubtreeUnder(head.id, newParentId = null, destinationGroupUuid = null) }
        }
        assertEquals("and the write takes it", listOf(head.id), accepted(outcome).movedTaskIds)
        assertTrue("its rows are destinations too",
            offeredParents(list).contains(stored().first { it.uuid == "draft" }.id))
    }

    @Test
    fun `duplicate titles in different branches carry different paths`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        row("notes", parent = "planning", position = 1, group = office.uuid, uuid = "notes-under-planning")
        row("receipts", position = 2, group = office.uuid)
        row("notes", parent = "receipts", position = 3, group = office.uuid, uuid = "notes-under-receipts")
        val head = row("figures", position = 4)

        // One title, two branches. A sheet that showed titles alone would draw two identical rows with no honest way
        // to say which is which — `pathTitles` is what tells them apart, and it is built from the tree rather than
        // from anything the caller has to remember about it.
        val twins = destinations(head).filter { it.parent?.title == "notes" }
        assertEquals("both are admissible, so both are drawn", 2, twins.size)
        assertEquals(setOf(
            listOf("office-block", "planning", "notes"),
            listOf("office-block", "receipts", "notes")
        ), twins.map { it.pathTitles }.toSet())
        assertEquals("the two rows are still two different rows",
            setOf("notes-under-planning", "notes-under-receipts"), twins.map { it.parent?.uuid }.toSet())
        twins.forEach { twin ->
            assertEquals("and a path always ends at the row it names", twin.parent?.title, twin.pathTitles.last())
        }
    }

    @Test
    fun `the destination names the level the head would land at`() {
        val office = group("office-block")
        val first = row("planning", position = 0, group = office.uuid)
        val second = row("draft", parent = "planning", position = 1, group = office.uuid)
        val head = row("figures", position = 2, group = office.uuid)

        val list = destinations(head)
        assertEquals("the topic's own child is level one, its child is two, and the deepest the contract holds is three",
            mapOf(
                (office.uuid to null) to 1,
                (null to null) to 1,
                (office.uuid to first.uuid) to 2,
                (office.uuid to second.uuid) to 3
            ),
            list.associate { (it.topic?.uuid to it.parent?.uuid) to it.resultingLevel }
        )

        // The number a reader is shown before the press, checked against the level the tree holds after it: a sheet
        // that promised the third level and wrote the second has said something untrue about the reader's notebook.
        val deepest = list.first { it.parent?.uuid == second.uuid }
        assertEquals(3, deepest.resultingLevel)
        val landed = withRestoredProfile {
            accepted(runBlocking { hierarchy.moveSubtreeUnder(head.id, deepest.parent?.id, deepest.topic?.uuid) })
            TaskHierarchyPolicy.levelsByUuid(stored()).getValue(head.uuid)
        }
        assertEquals("the sheet's number is the level the write produced", 3, landed)
    }

    @Test
    fun `the destination counts every task that travels with the branch`() {
        val office = group("office-block")
        val head = row("planning", position = 0, group = office.uuid)
        row("draft", parent = "planning", position = 1, group = office.uuid)
        row("figures", parent = "draft", position = 2, group = office.uuid)
        val leaf = row("notes", position = 3, group = office.uuid)

        val branch = destinations(head)
        assertTrue("a row with a child and a grandchild moves three tasks, the head included",
            branch.isNotEmpty() && branch.all { it.travellingCount == 3 })
        val single = destinations(leaf)
        assertTrue("and a row with nothing under it moves one",
            single.isNotEmpty() && single.all { it.travellingCount == 1 })

        val destination = branch.first { it.parent == null && it.topic?.uuid == office.uuid }
        val outcome = withRestoredProfile {
            runBlocking { hierarchy.moveSubtreeUnder(head.id, destination.parent?.id, destination.topic?.uuid) }
        }
        assertEquals("the count is the size of the block the write actually carries",
            destination.travellingCount, accepted(outcome).movedTaskIds.size)
    }

    @Test
    fun `a deeper branch is refused where a leaf would have fitted`() {
        val office = group("office-block")
        row("planning", position = 0, group = office.uuid)
        val second = row("draft", parent = "planning", position = 1, group = office.uuid)
        val leaf = row("figures", position = 2, group = office.uuid)
        val branch = row("notes", position = 3, group = office.uuid)
        row("receipts", parent = "notes", position = 4, group = office.uuid)

        // One destination row, and the only difference is what hangs under the head: a leaf lands on the third level
        // and is offered, a branch would put its child on a fourth and is not. This is the bound `fitsUnder` states
        // for a block rather than for the grabbed row — here as a destination rather than as a direction.
        assertTrue("the leaf fits under the second level",
            offeredParents(destinations(leaf)).contains(second.id))
        assertEquals("the branch does not, and is not drawn",
            false, offeredParents(destinations(branch)).contains(second.id))

        accepted(withRestoredProfile {
            runBlocking { hierarchy.moveSubtreeUnder(leaf.id, second.id, office.uuid) }
        })
        refusedByDomain(HierarchyMoveRefusal.DEPTH_EXCEEDED) {
            hierarchy.moveSubtreeUnder(branch.id, second.id, office.uuid)
        }
    }

    // ── the extraction ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The rule as the transaction composes it: the policy's answer, plus the one lookup only storage can do.
     *
     * [TaskHierarchyPolicy.moveRefusal] holds seven of the eight decisions; `PARENT_NOT_IN_PROFILE` for an id that
     * resolves to nothing is the repository's, because a caller names a parent by id and only storage says whether a
     * row answers. Rebuilding that composition here is what lets the table below compare two answers to one question
     * instead of comparing a copy with itself.
     */
    private fun refusalTheRuleGives(
        headId: Long,
        newParentId: Long?,
        destinationGroupUuid: String?
    ): HierarchyMoveRefusal? {
        val rows = stored()
        val facts = TaskHierarchyPolicy.factsOf(rows)
        val head = rows.first { it.id == headId }
        val block = TaskHierarchyPolicy.subtreeOf(rows, head)
        val destination = destinationGroupUuid?.takeIf { it.isNotBlank() }
        val parent = newParentId?.let { id -> rows.firstOrNull { it.id == id } }
        val owned = destination?.let { uuid ->
            val group = runBlocking { fixture.groupDao.getGroupByUuid(uuid) }
            if (group != null && group.userId == userId && !group.isDeleted) setOf(uuid) else emptySet()
        } ?: emptySet()
        return TaskHierarchyPolicy.moveRefusal(
            head = head,
            blockIds = block.map { it.id }.toSet(),
            blockHeight = TaskHierarchyPolicy.heightOf(rows, head),
            parent = parent,
            destinationGroupUuid = destination,
            profileTopicUuids = owned,
            facts = facts
        ) ?: if (newParentId != null && parent == null) HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE else null
    }

    /** One §5-pinned answer, checked against both halves: the rule over the same rows, and the write. */
    private fun assertOneRule(
        reason: HierarchyMoveRefusal,
        headId: Long,
        newParentId: Long?,
        destination: String?
    ) {
        assertEquals("the rule, read over the same rows", reason, refusalTheRuleGives(headId, newParentId, destination))
        refusedByDomain(reason) { hierarchy.moveSubtreeUnder(headId, newParentId, destination) }
    }

    /**
     * The eight refusals §5 wrote inline in `moveSubtreeUnder`, in the order §5 wrote them.
     *
     * §6.1 moved indent and outdent out of the transaction the same way and held the extraction to `HierarchyMoveTest`;
     * this is that proof for the move. Each case names the reason the older tests already pin, asks the policy the same
     * question over the same rows, and lets the repository answer for itself — so a reordered chain, a dropped check
     * or a refusal that quietly became an accept fails here rather than in a reader's notebook. The ninth row of the
     * table is the one case the chain lets through: a rule that refused everything would pass eight refusals and
     * leave a sheet whose every row is dead.
     */
    @Test
    fun `the extraction did not change what the repository decides`() {
        val office = group("office-block")
        val errand = group("errand-block")
        val stranger = runBlocking { fixture.signIn(name = "Other", email = "other@example.com").id }
        val foreignRow = runBlocking {
            fixture.taskDao.insertTask(
                Task(title = "theirs", description = "", uuid = "theirs", userId = stranger, order = 0)
            )
        }
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }

        // One profile holding a case per refusal, so the table is one fixture rather than eight seeds. The reason named
        // is the first one the order reaches for that row, which is the point of pinning the eight together.
        val inOffice = row("planning", position = 0, group = office.uuid)
        val secondLevel = row("draft", parent = "planning", position = 1, group = office.uuid)
        val leafHead = row("figures", position = 2, group = office.uuid)
        val branchHead = row("notes", position = 3, group = office.uuid)
        val carried = row("receipts", parent = "notes", position = 4, group = office.uuid)
        val twin = row("loose", position = 5, group = office.uuid, uuid = "twin")
        row("twin-second", position = 6, group = office.uuid, uuid = "twin")
        val nameless = row("nameless", uuid = "", position = 7, group = office.uuid)
        val inErrand = row("elsewhere", position = 8, group = errand.uuid)
        val before = shape()

        assertOneRule(HierarchyMoveRefusal.TOPIC_MISSING, leafHead.id, null, "no-such-group")
        assertOneRule(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE, leafHead.id, foreignRow, null)
        // The boundary between the two halves of the composition, and the only case where their order is sayable: a
        // request naming a topic this profile cannot hold *and* a parent that resolves to nothing is answered as the
        // topic, because §5 checked the destination before it looked the row up. Put the lookup first and this is the
        // row that notices.
        assertOneRule(HierarchyMoveRefusal.TOPIC_MISSING, leafHead.id, foreignRow, "no-such-group")
        assertOneRule(HierarchyMoveRefusal.SAME_ROW, leafHead.id, leafHead.id, office.uuid)
        assertOneRule(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE, branchHead.id, carried.id, office.uuid)
        assertOneRule(HierarchyMoveRefusal.PARENT_UNNAMED, leafHead.id, nameless.id, office.uuid)
        assertOneRule(HierarchyMoveRefusal.AMBIGUOUS_PARENT, leafHead.id, twin.id, office.uuid)
        assertOneRule(HierarchyMoveRefusal.TOPIC_MISMATCH, leafHead.id, inErrand.id, office.uuid)
        assertOneRule(HierarchyMoveRefusal.DEPTH_EXCEEDED, branchHead.id, secondLevel.id, office.uuid)
        assertEquals("and one legal destination is answered by neither half",
            null, refusalTheRuleGives(leafHead.id, inOffice.id, office.uuid))
        accepted(withRestoredProfile {
            runBlocking { hierarchy.moveSubtreeUnder(leafHead.id, inOffice.id, office.uuid) }
        })
        assertEquals("eight refusals and one accept, and the tree is still the tree", before, shape())
    }

    // ── the list's own determinism ──────────────────────────────────────────────────────────────────────────

    /**
     * Same rows, different listing order, same destinations.
     *
     * No destination appears or disappears because of the order the rows came in, and nothing the sheet prints about
     * one — level, travelling count, path — moves: the rules read [TreeFacts], which is a fact about the tree rather
     * than about a query's order. The order *of the list* is deliberately the caller's, so one render and the next
     * show the same rows in the same places; that is the half the two per-topic comparisons hold.
     *
     * Single identities only, on purpose: §4 records that two rows claiming one uuid make even a row's *level*
     * order-dependent, and `a parent whose identity is doubled is never offered` is where that case is held instead.
     */
    @Test
    fun `destinations do not depend on the order rows were listed in`() {
        val office = group("office-block")
        val errand = group("errand-block")
        row("planning", position = 0, group = office.uuid)
        row("draft", parent = "planning", position = 1, group = office.uuid)
        row("figures", position = 2, group = office.uuid)
        row("notes", position = 3, group = errand.uuid)
        val head = row("receipts", position = 4)
        row("loose", parent = "receipts", position = 5)

        val rows = stored()
        val forward = destinations(head, topics(), ownedTopicUuids(), TaskHierarchyPolicy.factsOf(rows))
        val backward = destinations(
            head, topics().reversed(), ownedTopicUuids(), TaskHierarchyPolicy.factsOf(rows.reversed())
        )
        assertTrue("the fixture has destinations to compare", forward.size > 3)
        assertEquals("the same destinations, whichever order the rows came in",
            forward.map { keyOf(it) }.toSet(), backward.map { keyOf(it) }.toSet())
        assertEquals("and the same thing to say about each one",
            forward.associate { keyOf(it) to it }, backward.associate { keyOf(it) to it })

        val officeForward = forward.filter { it.topic?.uuid == office.uuid }.map { keyOf(it) }
        val officeBackward = backward.filter { it.topic?.uuid == office.uuid }.map { keyOf(it) }
        assertEquals("a topic's own root stays first and its rows follow the sequence they were listed in",
            listOf(officeForward.first()) + officeForward.drop(1).reversed(), officeBackward)
        val errandForward = forward.filter { it.topic?.uuid == errand.uuid }.map { keyOf(it) }
        assertEquals("one root and one row for the topic that holds a single task",
            listOf(errand.uuid to null, errand.uuid to rows.first { it.uuid == "notes" }.id), errandForward)
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }
}
