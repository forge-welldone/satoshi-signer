package com.remotesigner

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remotesigner.data.MIGRATION_1_2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        "com.remotesigner.data.AppDatabase",
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_createsInboxItemsTable() {
        helper.createDatabase("migration-test", 1).close()

        helper.runMigrationsAndValidate(
            "migration-test",
            2,
            true,
            MIGRATION_1_2,
        )
    }
}
