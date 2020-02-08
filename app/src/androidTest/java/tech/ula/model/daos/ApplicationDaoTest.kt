package tech.ula.model.daos

import android.arch.core.executor.testing.InstantTaskExecutorRule
import android.arch.persistence.room.Room
import android.support.test.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.ula.blockingObserve
import tech.ula.model.entities.Application
import tech.ula.model.repositories.AppDatabase

class ApplicationDaoTest {

    @get:Rule
    val instantExectutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase

    @Before
    fun initDb() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getContext(),
                AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun insertApplicationAndGetByName() {
        val inserted = Application(DEFAULT_NON_AUTOGENERATED_ID, name = DEFAULT_NAME)

        db.applicationDao().insertApplication(inserted)
        val retrieved = db.applicationDao().getApplicationByName(DEFAULT_NAME)

        assertNotNull(retrieved)
        assertEquals(inserted, retrieved)
    }

    @Test
    fun dbApplicationIsReplacedOnConflict() {
        val app1 = Application(DEFAULT_NON_AUTOGENERATED_ID, name = "one")
        val app2 = Application(DEFAULT_NON_AUTOGENERATED_ID, name = "two")
        db.applicationDao().insertApplication(app1)
        db.applicationDao().insertApplication(app2)

        val retrieved = db.applicationDao().getAllApplications().blockingObserve()!!

        assertTrue(retrieved.contains(app2))
        assertFalse(retrieved.contains(app1))
    }

    companion object {
        val DEFAULT_NAME = "test"
        val DEFAULT_NON_AUTOGENERATED_ID = 1L
    }
}