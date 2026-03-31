package com.opencontacts.core.common

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RECORDING_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMddHHmm", Locale.US)
private val RECORDING_TIMESTAMP_PARSE_FORMAT = SimpleDateFormat("yyyyMMddHHmm", Locale.US)

data class CallRecordingItem(
    val id: String,
    val displayName: String,
    val uri: Uri,
    val contactName: String?,
    val phoneNumber: String?,
    val timestampMillis: Long?,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val sourceLabel: String,
)

fun buildCallRecordingFileName(contactName: String?, phoneNumber: String?, createdAt: Long = System.currentTimeMillis()): String {
    val safeName = sanitizeFileComponent(contactName).ifBlank { "Unknown" }
    val safePhone = sanitizeFileComponent(phoneNumber).ifBlank { "Unknown" }
    val timestamp = RECORDING_TIMESTAMP_FORMAT.format(Date(createdAt))
    return "$safeName ($safePhone) $timestamp.m4a"
}

fun listCallRecordings(
    context: Context,
    recordingsFolderUri: String?,
    recordingsPath: String,
): List<CallRecordingItem> {
    val fromTree = recordingsFolderUri
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)
        ?.let { treeUri ->
            runCatching {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching emptyList()
                root.listFiles()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        val name = file.name ?: return@mapNotNull null
                        val item = parseCallRecordingName(name)
                        CallRecordingItem(
                            id = file.uri.toString(),
                            displayName = name,
                            uri = file.uri,
                            contactName = item?.contactName,
                            phoneNumber = item?.phoneNumber,
                            timestampMillis = item?.timestampMillis,
                            modifiedAt = file.lastModified(),
                            sizeBytes = file.length(),
                            sourceLabel = root.name ?: "Recordings",
                        )
                    }
            }.getOrDefault(emptyList())
        }
        ?: emptyList()
    if (fromTree.isNotEmpty()) return fromTree.sortedByDescending { it.timestampMillis ?: it.modifiedAt }

    val localDir = File(context.filesDir, recordingsPath.ifBlank { "call_recordings" })
    if (!localDir.exists()) return emptyList()
    return localDir.listFiles()
        ?.filter { it.isFile }
        ?.mapNotNull { file ->
            val item = parseCallRecordingName(file.name) ?: return@mapNotNull null
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            CallRecordingItem(
                id = file.absolutePath,
                displayName = file.name,
                uri = uri,
                contactName = item.contactName,
                phoneNumber = item.phoneNumber,
                timestampMillis = item.timestampMillis,
                modifiedAt = file.lastModified(),
                sizeBytes = file.length(),
                sourceLabel = localDir.name,
            )
        }
        ?.sortedByDescending { it.timestampMillis ?: it.modifiedAt }
        ?: emptyList()
}

fun filterCallRecordingsForPhones(recordings: List<CallRecordingItem>, phoneNumbers: Collection<String>): List<CallRecordingItem> {
    val normalized = phoneNumbers.map(::normalizePhoneForMatching).filter { it.isNotBlank() }.toSet()
    if (normalized.isEmpty()) return emptyList()
    return recordings.filter { item ->
        val raw = item.phoneNumber ?: return@filter false
        normalizePhoneForMatching(raw) in normalized
    }
}

private data class ParsedCallRecordingName(
    val contactName: String?,
    val phoneNumber: String?,
    val timestampMillis: Long?,
)

private fun parseCallRecordingName(fileName: String): ParsedCallRecordingName? {
    val stem = fileName.substringBeforeLast('.')
    val match = Regex("^(.*) \\((.*)\\) (\\d{12})$").matchEntire(stem)
    return if (match != null) {
        val timestamp = runCatching { RECORDING_TIMESTAMP_PARSE_FORMAT.parse(match.groupValues[3])?.time }.getOrNull()
        ParsedCallRecordingName(
            contactName = match.groupValues[1].takeIf { it.isNotBlank() },
            phoneNumber = match.groupValues[2].takeIf { it.isNotBlank() },
            timestampMillis = timestamp,
        )
    } else {
        ParsedCallRecordingName(contactName = null, phoneNumber = null, timestampMillis = null)
    }
}

private fun sanitizeFileComponent(value: String?): String =
    value
        ?.trim()
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        ?.replace(Regex("\\s+"), " ")
        ?.take(80)
        .orEmpty()

private fun normalizePhoneForMatching(phone: String?): String =
    phone
        ?.filter { it.isDigit() || it == '+' }
        ?.trimStart('0')
        ?.ifBlank { phone.trim().orEmpty() }
        ?: ""
