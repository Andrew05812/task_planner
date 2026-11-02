package com.taskplanner.data.hierarchy

/**
 * How folding a branch ended.
 *
 * [Changed] carries the state the row was left in and how many rows that takes off the list, because the control has
 * to say what it did — a chevron that rotates while the rows it hides are not named is a reader wondering whether
 * the tap registered, and §7 asks for the number to be on the card. [Refused] carries a reason rather than a bool,
 * for the same reason a move does: the screen has to tell the reader something they can act on.
 */
sealed class HierarchyCollapseResult {
    data class Changed(
        val taskId: Long,
        val collapsed: Boolean,
        val hiddenDescendants: Int,
        val rowsWritten: Int
    ) : HierarchyCollapseResult()

    data class Refused(val reason: HierarchyCollapseRefusal) : HierarchyCollapseResult()
}

/** Why a branch was not folded. */
enum class HierarchyCollapseRefusal {
    /** No profile is signed in. */
    NO_PROFILE,

    /** The row is not a live row of this profile. */
    ROW_MISSING,

    /**
     * Nothing hangs under this row, so there is no branch to fold.
     *
     * A control over zero hidden rows is a chevron that lies about the reader's notebook, and §7 says the column must
     * not be added only to exist — so the request is refused rather than stored as a fold that hides nothing.
     */
    NO_CHILDREN,

    /**
     * Some identity inside the branch is claimed by more than one live row.
     *
     * The hidden set is found by walking parent links, so a doubled `uuid` gives "everything under it" two answers and
     * the card would report a number the list cannot reproduce. The same row makes a move and a delete refuse; a fold
     * is the least harmful of the three and still refuses, because a wrong count on the card is a wrong promise.
     */
    AMBIGUOUS_IDENTITY
}
