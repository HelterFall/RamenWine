package dev.ramenwine.inspection

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * Strict SAF directory enumeration.
 *
 * DocumentFile.listFiles() intentionally turns a failed DocumentsProvider
 * query (including a null Cursor) into an empty array. That is unsafe for game
 * import: an unreadable subfolder could otherwise look empty and an incomplete
 * copy could be reported as successful.
 */
object SafDocumentTree {
    fun listChildren(
        context: Context,
        directory: DocumentFile
    ): List<DocumentFile> {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                directory.uri,
                DocumentsContract.getDocumentId(directory.uri)
            )
        } catch (error: Exception) {
            throw IOException("Could not construct a SAF folder listing request.", error)
        }

        val cursor = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
        } catch (error: Exception) {
            throw IOException(
                "Android's document provider could not read folder: " +
                    (directory.name ?: "unnamed folder"),
                error
            )
        } ?: throw IOException(
            "Android's document provider returned no folder listing for: " +
                (directory.name ?: "unnamed folder")
        )

        val children = ArrayList<DocumentFile>()
        cursor.use { result ->
            val documentIdColumn = result.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            if (documentIdColumn < 0) {
                throw IOException("The document provider omitted document IDs from its folder listing.")
            }

            while (result.moveToNext()) {
                val documentId = result.getString(documentIdColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("The document provider returned an empty document ID.")

                val childUri = try {
                    DocumentsContract.buildDocumentUriUsingTree(directory.uri, documentId)
                } catch (error: Exception) {
                    throw IOException("Could not resolve a child document URI.", error)
                }

                val child = DocumentFile.fromTreeUri(context, childUri)
                    ?: throw IOException("Android could not open a child document.")
                children += child
            }
        }
        return children
    }
}
