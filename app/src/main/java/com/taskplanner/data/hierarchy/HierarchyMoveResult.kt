package com.taskplanner.data.hierarchy

/**
 * Why a request to change the tree did not happen, named so a screen can say it in the reader's language.
 *
 * A hierarchy edit refuses rather than doing something partial: a move that would put a row under another person's
 * row, into another topic, or inside its own subtree is not a thing the app silently repairs. The caller gets one of
 * these names and decides what — if anything — the reader is told.
 */
enum class HierarchyMoveRefusal {
    /** No profile is signed in, so there is nothing that could own the move. */
    NO_PROFILE,

    /** The row the request names is not a live row of this profile. */
    ROW_MISSING,

    /** The destination the request names is not a live row of this profile. */
    ANCHOR_MISSING,

    /** A row asked to be placed beside itself. */
    SAME_ROW,

    /** The destination is one of the rows that would travel with the dragged row, which would close a loop. */
    ANCHOR_IN_SUBTREE,

    /** The two rows hang under different topics: an ordinary reorder is not a move between topics. */
    DIFFERENT_TOPIC,

    /** The two rows hang under different parents: an ordinary reorder is not a reparenting. */
    DIFFERENT_PARENT,

    /**
     * More than one live row of this profile carries the identity the moved block is determined by, so "everything
     * under it" has no single answer. `tasks.uuid` has no unique index — Stage 16 §4 records why one was not added
     * blind — which is exactly why an ambiguous identity is refused at the write rather than resolved by a `LIMIT 1`.
     */
    AMBIGUOUS_PARENT,

    /** Nothing above the row could take it as a child: it is the first row of its profile. */
    NO_ROW_ABOVE,

    /**
     * The row above already holds this one, so an indent has nowhere deeper to go. It also answers a predecessor
     * with a blank identity, which cannot take a child — the same reason [AMBIGUOUS_PARENT] refuses a doubled one.
     */
    NO_DEEPER_PARENT,

    /** Already a topic's own child: outdenting further would leave the topic, which is not what an outdent means. */
    ALREADY_AT_TOPIC_ROOT,

    /** The row would land deeper than the contract holds — measured over its whole subtree, not over itself. */
    DEPTH_EXCEEDED,

    /** The destination row is not a live row of this profile, so it cannot be a parent of anything here. */
    PARENT_NOT_IN_PROFILE,

    /**
     * The destination row carries no identity of its own, so nothing can hang from it. Writing a blank into
     * `parent_uuid` would read back as "a topic's own child" — an un-parenting nobody asked for.
     */
    PARENT_UNNAMED,

    /** The destination topic names no topic this profile owns. */
    TOPIC_MISSING,

    /** The destination parent lives in a different topic from the one the request named. */
    TOPIC_MISMATCH
}

/**
 * How an edit of the tree ended.
 *
 * Both branches are results rather than exceptions because both are ordinary outcomes of a gesture: a drop that
 * lands outside the dragged row's sibling set happens as often as one that lands inside it, and the screen has to
 * answer each differently — [Accepted] lets the list keep what it drew, [Refused] lets it snap back to what the
 * database holds.
 */
sealed class HierarchyMoveResult {
    /**
     * @param movedTaskIds the rows that travelled, head of the subtree first, then its descendants. A plain drag
     *   moves a parent and everything under it as one piece, so the screen has to move the same list of rows it is
     *   told to, and no other row may end up above the parent it hangs from.
     * @param rowsRewritten how many stored rows the write actually touched. It is the measure of the strategy chosen
     *   in Stage 16 §3: a reorder renumbers the profile's one canonical sequence but writes only the rows whose
     *   position moved, so the cost of a drag is the distance it travelled rather than the size of the account.
     */
    data class Accepted(
        val movedTaskIds: List<Long>,
        val anchorTaskId: Long,
        val placeAfter: Boolean,
        val rowsRewritten: Int = 0
    ) : HierarchyMoveResult()

    data class Refused(val reason: HierarchyMoveRefusal) : HierarchyMoveResult()
}
