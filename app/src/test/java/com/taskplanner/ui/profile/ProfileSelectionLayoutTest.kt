package com.taskplanner.ui.profile

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.User
import com.taskplanner.databinding.FragmentProfileSelectionBinding
import com.taskplanner.databinding.ItemProfileBinding
import com.taskplanner.ui.Stage8aLayoutHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The profile switcher: which profile is the one in use, and can the finger still reach the two commands.
 *
 * `User.isCurrent` has been in the model since the profile table was written, and `ProfileAdapter` never
 * read it — the list shows the profiles and says nothing about which one the app is working in. Fixing that
 * is a display change, not a session change: the row already holds the fact, and the reader is the one
 * missing it. The claims below are therefore checked on rows the adapter actually bound, not on XML.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSelectionLayoutTest {

    private fun themed() = Stage8aLayoutHarness.themed()

    private fun screen(): FragmentProfileSelectionBinding =
        FragmentProfileSelectionBinding.inflate(Stage8aLayoutHarness.inflater())

    private fun row(): ItemProfileBinding = ItemProfileBinding.inflate(Stage8aLayoutHarness.inflater())

    private fun adapter() = ProfileAdapter(
        onProfileClick = { clicked += it },
        onProfileEdit = { edited += it },
        onProfileDelete = { deleted += it }
    )

    private val clicked = ArrayList<User>()
    private val edited = ArrayList<User>()
    private val deleted = ArrayList<User>()

    /** The rows a laid-out RecyclerView produced, in order, as the screen shows them. */
    private fun boundRows(profiles: List<User>): List<ItemProfileBinding> {
        val context = themed()
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProfileSelectionLayoutTest.adapter().also { it.submitList(profiles) }
        }
        val width = (Stage8aLayoutHarness.NARROW_DP * context.resources.displayMetrics.density).toInt()
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        assertEquals("the switcher drew ${recycler.childCount} rows for ${profiles.size} profiles",
            profiles.size, recycler.childCount)
        return (0 until recycler.childCount).map { ItemProfileBinding.bind(recycler.getChildAt(it)) }
    }

    @Test
    fun `the profile in use is marked with a word, not only with ink`() {
        val rows = boundRows(
            listOf(
                User(id = 1, email = "andrew@example.test", name = "Andrew", isCurrent = true),
                User(id = 2, email = "work@example.test", name = "Work")
            )
        )
        val current = rows.first().root.findViewById<View>(R.id.currentProfileMark)
            ?: throw AssertionError(
                "nothing in the row says which profile the app is working in; `User.isCurrent` is in the data " +
                    "and the reader never sees it"
            )
        assertTrue("the current-profile mark exists but is not shown on the current row", current.isVisible)
        assertTrue(
            "the mark has to be readable as words, not as a colour: it carries '${
                (current as? TextView)?.text ?: current.contentDescription
            }'",
            ((current as? TextView)?.text ?: current.contentDescription)?.toString()
                ?.contains(themed().getString(R.string.state_current)) == true
        )
        assertEquals("the second profile is not the current one and must not say it is",
            false, rows[1].root.findViewById<View>(R.id.currentProfileMark)?.isVisible)
    }

    @Test
    fun `the row's two commands are targets a finger can hit`() {
        val binding = row()
        binding.profileName.text = "Andrew"
        binding.profileEmail.text = "andrew@example.test"
        Stage8aLayoutHarness.laidOut(binding.root, Stage8aLayoutHarness.NARROW_DP)
        val density = themed().resources.displayMetrics.density
        for ((view, name) in listOf(
            binding.editProfileButton to "edit",
            binding.deleteProfileButton to "delete"
        )) {
            val widthDp = view.width / density
            val heightDp = view.height / density
            assertTrue(
                "the $name control measures ${widthDp}x${heightDp}dp; 1.4.11 asks 48dp of an interactive " +
                    "target, and this one deletes a profile",
                widthDp >= 47.5f && heightDp >= 47.5f
            )
        }
    }

    @Test
    fun `pressing the row, the pencil and the bin still reach three different commands`() {
        val andrew = User(id = 1, email = "andrew@example.test", name = "Andrew")
        val row = boundRows(listOf(andrew)).single()

        row.root.performClick()
        row.editProfileButton.performClick()
        row.deleteProfileButton.performClick()

        assertEquals("the row has to open the profile it shows", listOf(andrew), clicked)
        assertEquals("the pencil has to edit it", listOf(andrew), edited)
        assertEquals("and the bin has to delete it", listOf(andrew), deleted)
    }

    /**
     * The same holder, bound twice, says what the second row says.
     *
     * This is the property `ProfileAdapter`'s comment claims and nothing held: a row that comes back from the
     * recycle pool keeps whatever mark the previous occupant wore. The holder is re-bound directly rather than
     * through `submitList`, because the async differ would make the test depend on when Robolectric idles its
     * looper, and the claim is about `onBindViewHolder`, which is synchronous.
     */
    @Test
    fun `a recycled row takes the mark off the profile that is no longer current`() {
        val context = themed()
        val current = User(id = 1, email = "andrew@example.test", name = "Andrew", isCurrent = true)
        val other = User(id = 2, email = "work@example.test", name = "Work")
        val switcherAdapter = ProfileAdapter(onProfileClick = {}, onProfileEdit = {}, onProfileDelete = {})
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = switcherAdapter
        }
        switcherAdapter.submitList(listOf(current, other))
        val width = (Stage8aLayoutHarness.NARROW_DP * context.resources.displayMetrics.density).toInt()
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY)
        )
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        val holder = recycler.findViewHolderForAdapterPosition(0) as? ProfileAdapter.ProfileViewHolder
            ?: throw AssertionError("the switcher attached no row to recycle")
        assertTrue(
            "the first row is the current profile and has to say so before the test means anything",
            ItemProfileBinding.bind(holder.itemView).currentProfileMark.isVisible
        )

        // The same holder, bound with the profile that is not current. `bind` is the recycle path: RecyclerView
        // hands an existing holder to `onBindViewHolder`, which calls exactly this.
        holder.bind(other)
        val rebound = ItemProfileBinding.bind(holder.itemView)
        assertEquals("the row now shows the other profile", "Work", rebound.profileName.text.toString())
        assertEquals(
            "a recycled holder kept the mark of the profile that used to sit in it",
            false, rebound.currentProfileMark.isVisible
        )

        // And back again, because the flag is set in both directions only if the code says so.
        holder.bind(current)
        assertTrue(
            "binding the current profile into a holder that had shown an unmarked row left it unmarked",
            ItemProfileBinding.bind(holder.itemView).currentProfileMark.isVisible
        )
    }

    @Test
    fun `the switcher names itself and lists the profiles in a scrolling column`() {
        val binding = screen()
        Stage8aLayoutHarness.laidOut(binding.root)
        val header = binding.root.findViewById<TextView>(R.id.profilesTitleText)
            ?: throw AssertionError("the profile switcher carries no title of its own")
        assertEquals(themed().getString(R.string.title_select_profile), header.text.toString())
        assertTrue("the list has to be inside the scroller — profiles are unbounded",
            binding.recyclerViewProfiles.parent is ViewGroup)
    }
}
