package com.taskplanner.data.keep

import java.security.MessageDigest
import java.util.UUID

/** Which namespace a derived identity belongs to. A topic and a task are never the same row. */
enum class KeepNodeKind { TOPIC, TASK }

/**
 * The identity of an imported row, derived from the text it came from rather than generated at random.
 *
 * ## Why this exists at all
 *
 * §13 of the stage brief asks for the same paste to be importable twice without writing it twice. A `UUID` drawn
 * per row makes every paste a brand-new set of rows, so "already imported" cannot be detected and the reader gets
 * their whole notebook again. Identity therefore has to be a *function of the source*: the same words, in the same
 * profile, under the same parent, give the same uuids, and a second paste is met with a lookup instead of a shrug.
 *
 * ## What goes into the key, and what is deliberately left out
 *
 *  * the profile — two readers pasting one export must not collide with each other's rows. This is the namespace
 *    §13 asks for, and it is why nothing about a *device* or a *paste session* is in the key: an import is
 *    always a merge into the profile it lands in (§14), not a transaction that begins and ends here.
 *  * the parent's identity — `""` for a topic, the parent row's own uuid for anything below it. So a task's
 *    place in the tree is expressed by *what holds it*, and two same-named lines under two different topics are
 *    two rows.
 *  * the verbatim title — a re-worded line is a different row rather than an edit of the reader's task.
 *  * the occurrence index — the source repeats a title on purpose ([KeepNode] carries no uniqueness assumption),
 *    and the copies must stay separate and stable. Counted among the siblings that share it.
 *
 * **The line's position is not in the key, and the whole paste's fingerprint is not either.** That is a decision
 * with a consequence on both sides, and the preview has to state it rather than bury it (§11). Including the
 * document fingerprint would mean a paste whose text differs by one character re-imports every row of it — 382
 * duplicates for a typo fix; including the position would mean a line inserted at the top of a topic re-identifies
 * every line under it. Leaving both out makes an edited paste merge: one changed line becomes one new row, and
 * everything the reader did not touch keeps its identity. The price is that two *different* pastes whose root
 * lines happen to carry the same titles merge into the same topics, and that renaming a topic in Keep and pasting
 * again brings its subtree back as new rows. Both are shown before the confirm: the preview splits the paste into
 * what already exists and what is new, so a merge is something the reader sees, not something the app decided
 * behind their back.
 *
 * The result is a name-based uuid (`UUID.nameUUIDFromBytes`, MD5 over those bytes, version 3 layout). It is not a
 * hash somebody compares — it *is* the primary key, so determinism is the whole requirement.
 */
object KeepImportIdentity {

    /**
     * A stable hex digest of a paste, insensitive to how the line endings were written.
     *
     * `\r\n`, `\n` and `\r` and a leading byte-order mark are packaging, not content: the same notes copied
     * twice from Keep can arrive with any of them, and they must not look like two different sources. Case,
     * punctuation and whitespace *inside* a line do change it — that is the reader editing their notes, which
     * is exactly when rows are meant to become new ones.
     */
    fun fingerprintOf(source: String): String {
        val normalized = source.removePrefix("\uFEFF")
            .split("\r\n", "\n", "\r")
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * The uuid for one line of one paste.
     *
     * @param kind topic or task — the two namespaces are kept apart even for the same title under the same parent
     * @param userId the profile doing the import, which is also the namespace an import merges into
     * @param parentKey the holding row's uuid, or "" for a topic; see the class note for why a position is not
     *   part of this
     * @param title the verbatim title
     * @param occurrence which copy of an identical title under the same parent this is, counted from 0
     */
    fun uuidOf(kind: KeepNodeKind, userId: Long, parentKey: String, title: String, occurrence: Int): String {
        // NUL joins the fields, because a title containing any other separator must not be able to read as
        // two of them — and a title cannot contain this one, since the parser ends every line at a line break.
        val key = listOf("keep", kind.name, userId, parentKey, occurrence, title).joinToString("\u0000")
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }
}