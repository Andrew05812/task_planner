package com.taskplanner.ui.archive

import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.FragmentArchiveBinding
import com.taskplanner.databinding.ItemArchiveBinding
import com.taskplanner.databinding.ItemCategoryBinding
import com.taskplanner.ui.Stage8aLayoutHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The archive row, measured and bound.
 *
 * This is the screen where the spec's Completed treatment belongs — the Tasks list is not one of them, and
 * Stage 4 left it alone for that reason. Two things are checked here that a colour assertion can never
 * check: that a reader is told the row's state in a word and a shape as well as in ink, and that the two
 * commands on the row are still hittable after the restyle.
 *
 * One fact shaped the whole file. `TaskDao.getArchivedTasks` filters on `archived = 1 AND is_deleted = 0`
 * and on nothing else — no status condition — so a task can be archived while it is still TODO. The row the
 * screen shipped printed "Completed …" for every one of them, which is a claim the query does not support.
 * Stage 8a's review caught that a mark carrying its word only as a `contentDescription` fixes nothing for a
 * sighted reader, so the row now states its state in a visible word — finished or not finished — and the
 * line under the title says what its timestamp actually is: when the task was archived.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveLayoutTest {

    private fun themed() = Stage8aLayoutHarness.themed()

    private fun screen(): FragmentArchiveBinding =
        FragmentArchiveBinding.inflate(Stage8aLayoutHarness.inflater())

    private fun row(): ItemArchiveBinding = ItemArchiveBinding.inflate(Stage8aLayoutHarness.inflater())

    private fun task(id: Long, status: TaskStatus) = Task(
        id = id,
        title = "File the quarterly report",
        description = "",
        userId = 1L,
        status = status,
        archived = true,
        archivedAt = 1_700_000_000_000L
    )

    /** Rows the adapter itself bound, at 320dp, the way the screen shows them. */
    private fun boundRows(tasks: List<Task>): List<ItemArchiveBinding> {
        val context = themed()
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ArchiveAdapter(
                onRestore = {},
                onDeleteForever = {},
                onOpenDetail = {}
            ).also {
                it.submitList(tasks.map { one -> ArchiveState.ArchivedTask(one, null) })
            }
        }
        val width = (Stage8aLayoutHarness.NARROW_DP * context.resources.displayMetrics.density).toInt()
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        assertEquals("the archive drew ${recycler.childCount} rows for ${tasks.size} tasks",
            tasks.size, recycler.childCount)
        return (0 until recycler.childCount).map { ItemArchiveBinding.bind(recycler.getChildAt(it)) }
    }

    @Test
    fun `the search field, the filter row and the list are all in place before anything is read`() {
        val binding = screen()
        // The scroll exists so a populated filter row cannot overflow the width, and it is empty today:
        // nothing in `ArchiveFragment` adds a chip to `archiveCategoryChips`, even though
        // `ArchiveViewModel.setCategoryFilter` is waiting for one. Stage 8a may not add behaviour, so the row
        // is asserted as the container it is and the missing population is reported rather than papered over.
        assertTrue("the filter row scrolls sideways, which is what five categories at 320dp need",
            binding.archiveCategoryScroll.getChildAt(0) === binding.archiveCategoryChips)
        assertEquals("and nothing populates it yet — the finding the stage records, not hides",
            0, binding.archiveCategoryChips.childCount)
        assertTrue("and the list is the part that grows", binding.archiveList.parent === binding.root)
        assertEquals("the empty state waits for a row count of zero",
            false, binding.archiveEmpty.isVisible)
    }

    /**
     * Every archived row says which state it is in, in a word a reader can see.
     *
     * This test was written against a mark that carried its word only as a `contentDescription`, and the
     * review caught it: the assertion passed on text no sighted reader sees, while the row's visible line
     * read "Completed …" for a task that is still TODO. The word is now the task's own status label — the
     * same vocabulary the Tasks card and the category list already use — so the row cannot claim anything the
     * record does not say, and the timestamp line is named for what it actually holds: when the task was
     * archived.
     */
    @Test
    fun `a row names its state in a word a reader can see, and the word differs by status`() {
        val context = themed()
        val finished = boundRows(listOf(task(id = 1, status = TaskStatus.COMPLETED))).single()
        val open = boundRows(listOf(task(id = 2, status = TaskStatus.TODO))).single()

        val finishedMark = Stage8aLayoutHarness.require(finished.root, R.id.archiveStateMark, "the state mark")
            as TextView
        val openMark = Stage8aLayoutHarness.require(open.root, R.id.archiveStateMark, "the state mark")
            as TextView
        assertTrue("the state mark is in the tree but not shown", finishedMark.isVisible)
        assertEquals(
            "a finished row is labelled with the finished word the app already uses for that status",
            context.getString(R.string.status_done), finishedMark.text.toString()
        )
        assertEquals(
            "an archived-but-unfinished row is labelled with its own status — the row must not claim work " +
                "that is still open is done",
            context.getString(R.string.status_todo), openMark.text.toString()
        )
        assertTrue(
            "and the two words differ, so the state is told in text and not only in ink",
            finishedMark.text.toString() != openMark.text.toString()
        )
        val archivedWord = context.getString(R.string.archive_archived_at, "").trim()
        assertTrue(
            "the line under the title names the moment the task left the list ('$archivedWord …'): " +
                "'${open.archiveMeta.text}'",
            open.archiveMeta.text.toString().startsWith(archivedWord)
        )
        assertTrue(
            "and an unfinished row does not carry the finished word in its meta line either: " +
                "'${open.archiveMeta.text}'",
            !open.archiveMeta.text.toString().contains(context.getString(R.string.status_done))
        )
    }

    @Test
    fun `a finished task is struck through and an unfinished one is not`() {
        val rows = boundRows(
            listOf(task(id = 1, status = TaskStatus.COMPLETED), task(id = 2, status = TaskStatus.TODO))
        )
        val strike = Paint.STRIKE_THRU_TEXT_FLAG
        assertTrue(
            "a completed task's title has to be struck through; flags on ${rows[0].archiveTitle.paintFlags}",
            rows[0].archiveTitle.paintFlags and strike != 0
        )
        assertTrue(
            "and an archived-but-unfinished task must not look finished — the row would be lying about work " +
                "that is still open",
            rows[1].archiveTitle.paintFlags and strike == 0
        )
    }

    @Test
    fun `the two commands on an archived row are targets a finger can hit`() {
        val binding = row()
        Stage8aLayoutHarness.laidOut(binding.root)
        val density = themed().resources.displayMetrics.density
        for ((view, name) in listOf(
            binding.restoreButton to "restore",
            binding.deleteForeverButton to "delete forever"
        )) {
            val widthDp = view.width / density
            val heightDp = view.height / density
            assertTrue(
                "the $name control measures ${widthDp}x${heightDp}dp; 1.4.11 asks 48dp of a target, and " +
                    "delete-forever is the most destructive thing on the screen",
                widthDp >= 47.5f && heightDp >= 47.5f
            )
        }
    }

    @Test
    fun `a long title keeps both commands inside the card at 320dp`() {
        val binding = row()
        binding.archiveTitle.text = "Reconcile the supplier invoices from the third quarter and archive them"
        Stage8aLayoutHarness.laidOut(binding.root, Stage8aLayoutHarness.NARROW_DP)
        assertTrue(
            "the delete control ends at ${binding.deleteForeverButton.right}px outside a " +
                "${binding.root.width}px card",
            binding.deleteForeverButton.right <= binding.root.width
        )
        assertTrue("the title has to give room rather than push the actions out",
            binding.archiveTitle.right <= binding.restoreButton.left)
        assertTrue("and it says it was shortened instead of pretending to fit",
            binding.archiveTitle.ellipsize != null)
    }

    /**
     * The row's state is said *under* the task's name, not across it.
     *
     * This stage's 320dp frame showed "Stage 8a / unfinished a…". The state mark sat in the title's horizontal
     * path, and a mark plus two 48dp commands left the name ~86dp — twelve characters a line — so a title of
     * twenty-seven was cut while the row had room for it on a line of its own.
     *
     * The assertion the previous test could not make is the second one here. `a long title keeps both commands
     * inside the card at 320dp` compared the title with `restoreButton`, which sits on the far side of the
     * mark, so it passed on a row that was cutting the title. Whether a particular name fits the column these
     * measurements open up is not a question a JVM test can ask — Robolectric's LEGACY graphics measure text
     * as one pixel per character, the wall `TaskCardWidthBudgetTest` records from Stage 4 — so the seeded
     * title's character count is asked on the device, in
     * `Stage8aScreensInstrumentedTest.archiveOpensFromTheKebabAndItsRowsSayWhatTheyAre`.
     */
    @Test
    fun `the state mark is given a line of its own rather than a share of the title's`() {
        val binding = row()
        binding.archiveTitle.text = "Stage 8a unfinished archive"
        // The mark carries its word from the record, so the measurement has to give it one: an empty mark
        // takes no room, and a row measured that way is not the row the screen draws.
        (Stage8aLayoutHarness.require(binding.root, R.id.archiveStateMark, "the state mark") as TextView)
            .setText(R.string.status_todo)
        binding.archiveMeta.text = themed().getString(R.string.archive_archived_at, "Oct 28, 2026 23:40") +
            " · " + themed().getString(R.string.no_category)
        Stage8aLayoutHarness.laidOut(binding.root, Stage8aLayoutHarness.NARROW_DP)
        val mark = Stage8aLayoutHarness.require(binding.root, R.id.archiveStateMark, "the state mark")

        assertTrue(
            "the state mark sits in the title's path and takes the room a name needs: mark top=${mark.top} " +
                "against title bottom=${binding.archiveTitle.bottom}",
            mark.top >= binding.archiveTitle.bottom
        )
        assertTrue(
            "the title is cut off by the mark instead of by the commands: it ends at " +
                "${binding.archiveTitle.right}px, ${binding.restoreButton.left - binding.archiveTitle.right}px " +
                "short of the restore button",
            binding.archiveTitle.right >= binding.restoreButton.left - 1
        )
        assertTrue(
            "and the timestamp line gets the same column: it ends at ${binding.archiveMeta.right}px against " +
                "the restore button at ${binding.restoreButton.left}px",
            binding.archiveMeta.right >= binding.restoreButton.left - 1
        )
        assertTrue("both commands stay inside the card",
            binding.deleteForeverButton.right <= binding.root.width &&
                binding.restoreButton.right <= binding.root.width
        )
    }

    @Test
    fun `the archive title names the screen and sits above the search field`() {
        val binding = screen()
        Stage8aLayoutHarness.laidOut(binding.root)
        val header = binding.root.findViewById<TextView>(R.id.archiveTitleText)
            ?: throw AssertionError(
                "the archive screen has no title of its own, so the tab is the only thing that says where the " +
                    "reader is — Tasks, Profile and the editor all name themselves"
            )
        assertEquals(themed().getString(R.string.title_archive), header.text.toString())
        assertTrue("the title measured nothing, so the screen was never laid out to be ordered",
            header.height > 0)
        assertTrue(
            "the header has to be the first thing on the screen, not a label under the search box: header at " +
                "y=${Stage8aLayoutHarness.absoluteTop(header)}, search field at " +
                "y=${Stage8aLayoutHarness.absoluteTop(binding.archiveSearchLayout)}",
            Stage8aLayoutHarness.absoluteTop(header) <
                Stage8aLayoutHarness.absoluteTop(binding.archiveSearchLayout)
        )
    }

    /**
     * An archived row is told apart from a live one without talking about colour.
     *
     * The comparison is against the category card, the other list row on the same system: same corner, same
     * elevation, and then a mark the live row does not carry. If a future edit moves the treatment into a
     * tint alone, this fails and says what it measured.
     */
    @Test
    fun `an archived row is distinguishable from a live card without naming a colour`() {
        val archived = boundRows(listOf(task(id = 1, status = TaskStatus.COMPLETED))).single().root
        val live = ItemCategoryBinding.inflate(Stage8aLayoutHarness.inflater()).root
        Stage8aLayoutHarness.laidOut(live)
        assertTrue(
            "the archived card carries no mark a live card lacks: archived children " +
                "${archived.childCount}, live ${live.childCount}",
            Stage8aLayoutHarness.allViews(archived).any { it.id == R.id.archiveStateMark && it.isVisible } &&
                Stage8aLayoutHarness.allViews(live).none { it.id == R.id.archiveStateMark }
        )
        assertTrue("and the row's actions are real targets, not painted text",
            Stage8aLayoutHarness.allViews(archived).filterIsInstance<View>().any {
                it.isClickable && it.width >= (47.5f * themed().resources.displayMetrics.density).toInt()
            })
        assertTrue("a card is still a card: the archive row is inside a ViewGroup",
            archived is ViewGroup)
    }
}
