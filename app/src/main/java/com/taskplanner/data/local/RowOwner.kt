package com.taskplanner.data.local

/**
 * Which profile holds a row, asked by uuid.
 *
 * An import derives its identity from the text it read, so a uuid it is about to write may already belong to
 * somebody — either this same profile, from an earlier paste of the same text (skip it), or another one (refuse
 * the import). Only the owner can tell those apart, so the lookups that back the decision come home as this pair
 * rather than as a bare set of uuids.
 */
data class RowOwner(
    val uuid: String,
    val userId: Long
)
