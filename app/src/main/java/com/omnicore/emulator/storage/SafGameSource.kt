package com.omnicore.emulator.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * Small Storage Access Framework helper used by PS1 folder/CUE imports.
 * It intentionally avoids copying game images during library import.
 */
object SafGameSource {
    data class Document(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long = 0L,
        val mimeType: String? = null,
        val lastModifiedMillis: Long = 0L
    ) {
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun listDirectChildren(context: Context, treeUri: Uri): List<Document> {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        return buildList {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val documentId = if (idIndex >= 0) cursor.getString(idIndex) else continue
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else documentId
                    val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    add(
                        Document(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            sizeBytes = size,
                            mimeType = mime,
                            lastModifiedMillis = modified
                        )
                    )
                }
            }
        }
    }

    fun metadata(context: Context, uri: Uri): Document {
        val resolver = context.contentResolver
        // OpenableColumns are broadly supported by arbitrary document/content
        // providers. LAST_MODIFIED is intentionally not requested here because
        // some non-DocumentsProvider sources reject unknown projection columns.
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "game"
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                return Document(uri = uri, name = name, sizeBytes = size)
            }
        }
        return Document(uri = uri, name = uri.lastPathSegment ?: "game")
    }

    fun readCueText(context: Context, cueUri: Uri): String {
        val bytes = context.contentResolver.openInputStream(cueUri).use { input ->
            requireNotNull(input) { "Não consegui ler o arquivo CUE." }
            input.readBytes()
        }
        val utf8 = bytes.toString(Charsets.UTF_8)
        val decoded = if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
        return decoded.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }

    fun cueReferences(cueText: String): List<String> =
        CUE_FILE_LINE.findAll(cueText)
            .mapNotNull { match ->
                val quoted = match.groupValues[2]
                val plain = match.groupValues[3]
                (quoted.ifBlank { plain }).takeIf { it.isNotBlank() }
            }
            .distinctBy { normalizeReference(it).lowercase() }
            .toList()

    fun rewriteCueReferences(cueText: String, resolvedNames: Map<String, String>): String =
        CUE_FILE_LINE.replace(cueText) { match ->
            val original = match.groupValues[2].ifBlank { match.groupValues[3] }
            val key = normalizeReference(original).lowercase()
            val resolved = resolvedNames[key] ?: original
            "${match.groupValues[1]}\"$resolved\"${match.groupValues[4]}"
        }

    fun normalizeReference(reference: String): String =
        reference.trim().replace('\\', '/').substringAfterLast('/')

    private val CUE_FILE_LINE = Regex(
        pattern = "(?im)^(\\s*FILE\\s+)(?:\\\"([^\\\"]+)\\\"|(\\S+))(\\s+.+)$"
    )
}
