package com.taskplanner.data.sync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.testing.AppFixture
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

/**
 * Without a Firebase configuration (no google-services.json, no keys committed) the app must stay
 * fully usable offline. These tests pin that contract: nothing may be uploaded, nothing may be
 * marked as synced, and the Firestore client must not even be created.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun firestoreThatFailsWhenTouched(): Lazy<FirebaseFirestore> =
        Lazy { throw AssertionError("Firebase must not be initialized in local-only mode") }

    private fun repository(): SyncRepository = SyncRepository(
        firestoreThatFailsWhenTouched(),
        fixture.sessionRepository,
        fixture.taskDao,
        fixture.categoryDao,
        fixture.groupDao,
        fixture.recurrenceDao,
        fixture.reminderDao,
        fixture.syncAppliers,
        fixture.recurrenceRepository,
        fixture.reminderRepository,
        fixture.prefs
    )

    @Test
    fun `this build is not cloud configured`() {
        // docs/FIREBASE_SETUP.md explains how a real project turns this on; the repository must
        // never ship credentials, so CI always exercises the local-only branch.
        assertFalse(CloudConfig.isConfigured)
    }

    @Test
    fun `sync reports cloud disabled and leaves the queue untouched`() = runTest {
        val user = fixture.signIn()
        val taskId = fixture.addTask(
            user.id, "not uploaded yet", dueAt = System.currentTimeMillis() + 3_600_000L
        )
        val task = fixture.taskDao.getTaskById(taskId)!!
        fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)
        fixture.reminderRepository.applyReminders(fixture.taskDao.getTaskById(taskId)!!, listOf(15))
        val categoryId = fixture.addCategory(user.id, "Work")
        val group = fixture.addGroup(user.id, "Sprint")

        val sync = repository()

        assertEquals(SyncStatus.CloudDisabled, sync.status.value)
        assertEquals(SyncStatus.CloudDisabled, sync.syncNow())

        assertEquals(SyncState.PENDING, fixture.taskDao.getTaskById(taskId)?.syncState)
        assertEquals(SyncState.PENDING, fixture.categoryDao.getCategoryById(categoryId)?.syncState)
        assertEquals(SyncState.PENDING, fixture.groupDao.getGroupByUuid(group.uuid)?.syncState)
        assertEquals(
            "a series the cloud has never seen stays in the queue",
            SyncState.PENDING, fixture.recurrenceDao.getAllRowsOnce(user.id).single().syncState
        )
        assertEquals(
            "a lead time the cloud has never seen stays in the queue",
            SyncState.PENDING, fixture.reminderDao.getAllRowsOnce(user.id).single().syncState
        )
    }

    @Test
    fun `repeated sync attempts stay safe without a cloud`() = runTest {
        val user = fixture.signIn()
        fixture.addTask(user.id, "one")
        fixture.addTask(user.id, "two")
        val sync = repository()

        repeat(3) { assertEquals(SyncStatus.CloudDisabled, sync.syncNow()) }

        assertEquals(2, fixture.taskDao.getAllRowsOnce(user.id).size)
        assertEquals(2, fixture.taskDao.getPendingSyncTasks().size)
    }

    @Test
    fun `scheduling sync is a no-op when the cloud is off`() {
        // WorkManager is not even initialized in this test process: reaching it would throw.
        SyncScheduler.ensurePeriodic(context)
        SyncScheduler.requestNow(context)
    }
}
