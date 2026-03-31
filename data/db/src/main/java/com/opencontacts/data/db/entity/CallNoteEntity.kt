package com.opencontacts.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_notes",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("contact_id"),
        Index("normalized_phone"),
        Index("created_at"),
        Index("call_started_at"),
    ],
)
data class CallNoteEntity(
    @PrimaryKey @ColumnInfo(name = "call_note_id") val callNoteId: String,
    @ColumnInfo(name = "contact_id") val contactId: String?,
    @ColumnInfo(name = "normalized_phone") val normalizedPhone: String,
    @ColumnInfo(name = "raw_phone") val rawPhone: String? = null,
    @ColumnInfo(name = "direction") val direction: String? = null,
    @ColumnInfo(name = "call_started_at") val callStartedAt: Long? = null,
    @ColumnInfo(name = "call_ended_at") val callEndedAt: Long? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int? = null,
    @ColumnInfo(name = "phone_account_label") val phoneAccountLabel: String? = null,
    @ColumnInfo(name = "note_text") val noteText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
