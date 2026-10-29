package com.taskplanner.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.testing.AppFixture
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The copy the handover is not allowed to start without, on its own.
 *
 * `AccountAttachTest` proves the *move*; what this class proves is the thing the stage's ordering turns on — that
 * a verified copy exists before anything is deleted, and that "verified" means the app read the copy back rather
 * than wrote a file and hoped.
 *
 * Written after the production code, and said plainly: the RED of this stage was `AccountAttachTest` and the
 * Settings tests, which failed first and are what the implementation was written against. These are the
 * failure-path locks the reviewer's question "would a bad snapshot ever be accepted?" needs an answer to. Each one
 * was run against the code as it stood, and two of them were watched failing before the fix that made them pass —
 * the prune test, which kept all five snapshots and ten sidecars on disk, and nothing else.
 *
 * **What is not covered here, stated rather than implied.** `ProfileSafetyCopy` refuses in five ways; this class
 * reaches three of them (a file that is not a database, a file that is not there, and a snapshot that holds a
 * different count than the profile owned). The other two — the write-ahead log still holding frames, and the
 * database or the profile changing while the copy is taken — cannot be made to happen on Robolectric's legacy
 * SQLite layer, where `wal_checkpoint(TRUNCATE)` always reports every frame written, and there is no second writer
 * to race with. Their guard is the measurement written down in `docs/AUTH_SYNC_ENGINEERING.md` §6 and the plan
 * record, and the arm that matters in production is exercised on real Android SQLite by
 * `SignInDataPreservationInstrumentedTest`, which runs a handover and checks the snapshot it took.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSafetyCopyTest {

    private lateinit var fixture: AppFixture
    private lateinit var local: com.taskplanner.data.model.User

    /** The limit Windows applies to a path handed to the C library, which is what SQLite asks for. */
    private val MAX_PATH = 260

    @Before
    fun signInALocalProfile() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        local = kotlinx.coroutines.runBlocking { fixture.signIn() }
    }

    @After
    fun release() = fixture.close()

    private val safetyCopy get() = fixture.safetyCopy

    @Test
    fun `the snapshot reopens as a database holding exactly the rows the profile owned`() = runTest {
        fixture.addTask(local.id, "Pay the rent")
        fixture.addTask(local.id, "Send the invoice")
        fixture.addCategory(local.id, "Home")
        fixture.awaitIdle()

        val snapshot = safetyCopy.take(reason = "reopen", profileUserId = local.id)

        assertTrue("the snapshot is not on disk: ${snapshot.file}", snapshot.file.exists())
        assertEquals(
            "what the copy holds is what the profile owned at the instant it was taken",
            snapshot.rowsOfProfile, safetyCopy.verifyReadable(snapshot.file, local.id)
        )
        assertEquals(
            "and the two tasks and one category the profile had are in it",
            mapOf("tasks" to 2, "categories" to 1),
            snapshot.rowsOfProfile.filterKeys { it in listOf("tasks", "categories") }
        )
    }

    /**
     * The snapshot's own path stays inside the limit the file system has.
     *
     * `filesDir/safety/` under Robolectric is under a directory named after the test class *and* the test method,
     * so a long method title is spent from a 260-character budget that Windows enforces — and SQLite reports a
     * path it cannot open as "unable to open database file", which reads exactly like a handover with a broken
     * copy. The one place a harness defect like that should surface is here, naming the path and its length, not
     * three tables of attach tests failing for a reason nobody would think to look for.
     */
    @Test
    fun `the snapshot lands where the file system can still name it`() = runTest {
        fixture.addTask(local.id, "Any row will do")
        fixture.awaitIdle()

        val snapshot = safetyCopy.take(reason = "attach", profileUserId = local.id)
        val path = snapshot.file.absolutePath

        assertTrue(
            "Windows refuses a path over $MAX_PATH characters and reports it as an unopenable database; this " +
                "test's own name is the variable that eats the budget. Path (${path.length} chars): $path",
            path.length <= MAX_PATH
        )
    }

    @Test
    fun `every table a profile owns is counted, including the ones it has nothing in`() = runTest {        fixture.addTask(local.id, "Only one kind of row")
        fixture.awaitIdle()

        val snapshot = safetyCopy.take(reason = "tables", profileUserId = local.id)

        assertEquals(
            "a snapshot that silently skipped the eleven newer tables is the defect this class exists to prevent, " +
                "so the table list is asserted rather than implied by a count",
            ProfileSafetyCopy.PROFILE_TABLES.toSet(), snapshot.rowsOfProfile.keys
        )
        assertEquals("a table with nothing in it reads zero, not missing", 0, snapshot.rowsOfProfile["habits"])
    }

    @Test
    fun `a snapshot that is not on disk is refused by name`() {
        val missing = File(fixture.context.filesDir, "safety/profile-gone-0.db")
        val failure = runCatching { safetyCopy.verifyReadable(missing, local.id) }.exceptionOrNull()
        assertTrue(
            "expected a refusal naming the file, got: $failure",
            failure is ProfileSafetyCopy.SafetyCopyUnavailable &&
                (failure.message ?: "").contains("profile-gone-0.db")
        )
    }

    @Test
    fun `a file that is not a database is refused rather than read as empty`() {
        val garbage = File.createTempFile("not-a-database", ".db").apply {
            deleteOnExit()
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }

        val failure = runCatching { safetyCopy.verifyReadable(garbage, local.id) }.exceptionOrNull()

        assertTrue(
            "a file SQLite cannot open must stop the operation, not answer with zero rows: $failure",
            failure is ProfileSafetyCopy.SafetyCopyUnavailable
        )
    }

    @Test
    fun `only the newest snapshots are kept, and a snapshot's sidecars go with it`() = runTest {
        fixture.addTask(local.id, "Any row will do")
        fixture.awaitIdle()
        val directory = File(fixture.context.filesDir, "safety")
        // Distinct reasons and a gap between takes: the file name carries the millisecond and the pruning rule
        // sorts by `lastModified`, which on a Windows filesystem is coarser than a millisecond. Five takes inside
        // one timestamp would be five names for one file and a "newest three" that cannot be decided — a detail of
        // this machine's clock, not of the rule, so the test waits rather than loosening what it asserts.
        //
        // A `-wal` and a `-shm` are planted beside the oldest snapshot after every take, because that is what
        // SQLite leaves beside a copy this class has opened for verification. The rule has to carry them away with
        // their snapshot — both because they are the reader's data and because a prune that counted them as
        // separate snapshots would keep two logs and a copy and drop a third live copy instead.
        val taken = (1..5).map {
            safetyCopy.take(reason = "prune$it", profileUserId = local.id).also { snapshot ->
                listOf("-wal", "-shm").forEach { suffix ->
                    File(directory, snapshot.file.name + suffix).writeBytes(byteArrayOf(0))
                }
                Thread.sleep(25)
            }
        }

        val names = directory.listFiles()!!.map { it.name }.sorted()
        val dropped = taken.take(2).map { it.file.name.removeSuffix(".db") }
        val survivors = taken.takeLast(3).map { it.file.name }.sorted()

        assertTrue(
            "the folder is the reader's disk, not a place to accumulate copies of their life. Files there: " +
                names.joinToString(),
            dropped.none { stem -> names.any { it.startsWith(stem) } }
        )
        assertEquals(
            "and the newest three are the ones that survive, with no rejected attempt among them either",
            survivors, names.filter { it.endsWith(".db") }.sorted()
        )
    }

    /**
     * A copy that failed a check is the same kind of file as one that passed, and is kept to the same limit.
     *
     * `reject` renames a refused snapshot to `rejected-*.db` so the mismatch keeps its evidence, and evidence has
     * to be comparable with the thing it proves. What this asserts is that a rejected attempt is not exempt from
     * the "newest three" rule and not exempt from the sidecar sweep either: a folder of `rejected-*.db` files
     * would be a folder of the reader's data that no limit applies to.
     */
    @Test
    fun `a rejected attempt is pruned like the snapshots`() = runTest {
        fixture.addTask(local.id, "Any row will do")
        fixture.awaitIdle()
        val directory = File(fixture.context.filesDir, "safety").apply { mkdirs() }
        // An attempt from an earlier day: older than everything this test is about to take.
        val stale = File(directory, "rejected-1.db").apply { writeText("not a snapshot") }
        listOf("-wal", "-shm").forEach { suffix -> File(directory, "rejected-1.db$suffix").writeText("x") }
        val earlier = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        assertTrue(
            "the harness could not back-date the rejected file it is about to prune",
            stale.setLastModified(earlier)
        )

        // Four files against a limit of three: the three copies this test takes, and the attempt from a day ago.
        (1..3).forEach { safetyCopy.take(reason = "after$it", profileUserId = local.id) }

        val names = directory.listFiles()!!.map { it.name }.sorted()
        assertTrue(
            "a rejected copy is the reader's data too, and must not outlive the limit: $names",
            names.none { it.startsWith("rejected-") }
        )
        assertEquals(
            "the three newest snapshots are all that is left, and nothing else is in the folder",
            3, names.count { it.startsWith("profile-after") && it.endsWith(".db") }
        )
        assertEquals("and no sidecar of the removed attempt stayed behind", 3, names.size)
    }

    @Test
    fun `another profile's rows are not in the count the snapshot is checked against`() = runTest {
        val other = fixture.signInAs(
            com.taskplanner.data.model.User(email = "other@example.com", name = "Other")
        )
        fixture.addTask(other.id, "Not mine")
        fixture.addTask(local.id, "Mine")
        fixture.awaitIdle()

        val snapshot = safetyCopy.take(reason = "scoped", profileUserId = local.id)

        assertEquals(
            "the copy is of the whole database — which is the point — but the verification counts *this* profile, " +
                "so a snapshot that lost the reader's rows to somebody else's would still fail",
            mapOf("tasks" to 1), snapshot.rowsOfProfile.filterKeys { it == "tasks" }
        )
        assertEquals(
            "and the other profile keeps its own row, unread by this snapshot but untouched by it",
            listOf("Not mine"), fixture.database.taskDao().getAllRowsOnce(other.id).map { it.title }
        )
    }
}
