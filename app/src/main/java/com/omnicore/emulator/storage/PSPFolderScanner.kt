package com.omnicore.emulator.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * PSP-only recursive SAF scanner.
 *
 * Isolated from SafGameSource's legacy direct-child paths so PS1 media grouping
 * and other existing library imports keep their prior folder semantics.
 */
object PSPFolderScanner {
    /**
     * Lists files below a selected tree, bounded to avoid walking an unexpectedly
     * huge or cyclic document-provider hierarchy. The existing direct-child path
     * remains unchanged for legacy console imports that depend on folder layout.
     */
    fun listRecursively(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 8,
        maxEntries: Int = 4096
    ): List<Document> {
        require(maxDepth >= 0) { "maxDepth must not be negative" }
        require(maxEntries > 0) { "maxEntries must be positive" }

        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<Document>()
        val visitedDirectories = mutableSetOf<String>()
        var inspectedEntries = 0

        fun visit(parentDocumentId: String, depth: Int) {
            if (depth > maxDepth || inspectedEntries >= maxEntries) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext() && inspectedEntries < maxEntries) {
                        val documentId = if (idIndex >= 0) cursor.getString(idIndex) else continue
                        inspectedEntries++
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else documentId
                        val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            cursor.getLong(modifiedIndex)
                        } else 0L
                        val item = Document(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            sizeBytes = size,
                            mimeType = mime,
                            lastModifiedMillis = modified
                        )
                        if (item.isDirectory) {
                            if (depth < maxDepth && visitedDirectories.add(documentId)) {
                                visit(documentId, depth + 1)
                            }
                        } else {
                            result += item
                        }
                    }
                }
            }
        }

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        visitedDirectories += rootId
        visit(rootId, 0)
        return result
    }
}
