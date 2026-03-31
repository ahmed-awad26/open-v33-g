package com.opencontacts.data.db.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opencontacts.data.db.dao.ContactsDao
import com.opencontacts.data.db.entity.BackupRecordEntity
import com.opencontacts.data.db.entity.CallNoteEntity
import com.opencontacts.data.db.entity.ContactEntity
import com.opencontacts.data.db.entity.ContactFolderCrossRef
import com.opencontacts.data.db.entity.ContactTagCrossRef
import com.opencontacts.data.db.entity.FolderEntity
import com.opencontacts.data.db.entity.ImportExportHistoryEntity
import com.opencontacts.data.db.entity.NoteEntity
import com.opencontacts.data.db.entity.ReminderEntity
import com.opencontacts.data.db.entity.TagEntity
import com.opencontacts.data.db.entity.TimelineEntity

@Database(
    entities = [
        ContactEntity::class,
        NoteEntity::class,
        CallNoteEntity::class,
        ReminderEntity::class,
        TimelineEntity::class,
        BackupRecordEntity::class,
        ImportExportHistoryEntity::class,
        TagEntity::class,
        FolderEntity::class,
        ContactFolderCrossRef::class,
        ContactTagCrossRef::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao
}

val VAULT_DB_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE folders ADD COLUMN image_uri TEXT")
        database.execSQL("ALTER TABLE folders ADD COLUMN description TEXT")
        database.execSQL("ALTER TABLE folders ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE folders ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
    }
}

val VAULT_DB_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contacts ADD COLUMN external_links_json TEXT NOT NULL DEFAULT '[]'")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_folder_cross_ref (
                contact_id TEXT NOT NULL,
                folder_name TEXT NOT NULL,
                PRIMARY KEY(contact_id, folder_name),
                FOREIGN KEY(contact_id) REFERENCES contacts(contact_id) ON DELETE CASCADE,
                FOREIGN KEY(folder_name) REFERENCES folders(folder_name) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_contact_folder_cross_ref_contact_id ON contact_folder_cross_ref(contact_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_contact_folder_cross_ref_folder_name ON contact_folder_cross_ref(folder_name)")
        database.execSQL(
            """
            INSERT OR IGNORE INTO contact_folder_cross_ref(contact_id, folder_name)
            SELECT contact_id, folder_name
            FROM contacts
            WHERE folder_name IS NOT NULL AND TRIM(folder_name) != ''
            """.trimIndent()
        )
    }
}


val VAULT_DB_MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contacts ADD COLUMN block_mode TEXT NOT NULL DEFAULT 'NONE'")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_block_mode ON contacts(block_mode)")
        database.execSQL("UPDATE contacts SET block_mode = CASE WHEN is_blocked = 1 THEN 'INSTANT_REJECT' ELSE 'NONE' END")
    }
}


val VAULT_DB_MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE contacts ADD COLUMN phone_numbers_json TEXT NOT NULL DEFAULT '[]'")
    }
}


val VAULT_DB_MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS call_notes (
                call_note_id TEXT NOT NULL PRIMARY KEY,
                contact_id TEXT,
                normalized_phone TEXT NOT NULL,
                raw_phone TEXT,
                direction TEXT,
                call_started_at INTEGER,
                call_ended_at INTEGER,
                duration_seconds INTEGER,
                phone_account_label TEXT,
                note_text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(contact_id) ON DELETE SET NULL
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_call_notes_contact_id ON call_notes(contact_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_call_notes_normalized_phone ON call_notes(normalized_phone)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_call_notes_created_at ON call_notes(created_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_call_notes_call_started_at ON call_notes(call_started_at)")
    }
}
