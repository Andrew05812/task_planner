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
 * The stage brief's §13 asks for the same paste to be importable twice without writing it twice. A `UUID`
 * drawn per row makes every paste a brand-new set of rows, so "already imported" is undetectable and the
 * reader gets their whole notebook a second time. Identity therefore has to be a *function of the source*:
 * the same text, in the same profile, gives the same uuids, and a second paste can be met with a lookup
 * instead of a shrug.
 *
 * ## What goes into the key
 *
 *  * the profile — two readers pasting the same export must not collide with each other's rows;
 *  * the fingerprint of the pasted text — so a row's identity is scoped to the document it came from, and
 *    editing the text cannot silently reach a row an earlier paste wrote from different words;
 *  * the structural path (`"3/1/0"` — third topic, second child of it, first child of that) — the position
 *    the line holds in the tree, which is what indentation actually says;
 *  * the title — so a line that changed wording is a different row rather than an edit of someone's task;
 *  * the occurrence index — the source contains the same title twice on purpose ([KeepNode] carries no
 *    uniqueness assumption), and the two copies must be two rows with stable, different identities.
 *
 * The result is a name-based uuid (`UUID.nameUUIDFromBytes`, MD5 over those bytes, version 3 layout). It is
 * not a hash the database compares — it *is* the primary key, so determinism is the whole requirement and
 * collision resistance is provided by the fields above rather than by the digest.
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
     * The uuid for one node of one paste.
     *
     * @param kind topic or task — the two namespaces are kept apart even at the same path and title
     * @param userId the profile doing the import
     * @param fingerprint [fingerprintOf] the pasted text
     * @param path structural position in the parsed tree, e.g. `"2"` or `"2/0/1"`
     * @param title the verbatim title, since a re-worded line is a different row
     * @param occurrence which copy of an identical title this is, counted per parent, from 0
     */
    fun uuidOf(
        kind: KeepNodeKind,
        userId: Long,
        fingerprint: String,
        path: String,
        title: String,
        occurrence: Int
    ): String {
        // The separators are part of the key: path "1/11" + title "2" must not read as path "11/1" + title "".
        val key = "keep|${kind.name}|$userId|$fingerprint|$path|$occurrence|$title"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }
}
