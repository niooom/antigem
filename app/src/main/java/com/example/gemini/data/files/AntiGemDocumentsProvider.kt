package com.example.gemini.data.files

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.example.gemini.MainActivity
import com.example.gemini.R
import java.io.File
import java.io.FileNotFoundException

class AntiGemDocumentsProvider : DocumentsProvider() {

    private companion object {
        const val ROOT_ID = "antigem_root"
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }

    override fun onCreate(): Boolean = true

    private fun baseDir(): File {
        val ctx = context ?: throw FileNotFoundException("Missing provider context")
        val home = File(ctx.filesDir, "home")
        return if (home.exists() && home.isDirectory) home else ctx.filesDir
    }

    private fun docIdForFile(file: File): String {
        val base = baseDir().canonicalFile
        val target = file.canonicalFile
        if (target == base) return ROOT_ID
        val rel = target.relativeTo(base).path
        return "$ROOT_ID/$rel"
    }

    private fun fileForDocId(documentId: String): File {
        val base = baseDir().canonicalFile
        if (documentId == ROOT_ID) return base
        if (!documentId.startsWith("$ROOT_ID/")) {
            throw FileNotFoundException("Invalid document ID: $documentId")
        }
        val rel = documentId.removePrefix("$ROOT_ID/")
        val target = File(base, rel).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw FileNotFoundException("Access denied: $documentId")
        }
        if (!target.exists()) {
            throw FileNotFoundException("File not found: $documentId")
        }
        return target
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = context ?: return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val base = baseDir()
        val title = ctx.applicationInfo.loadLabel(ctx.packageManager).toString().ifBlank { "Agent Gravity" }

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_SUMMARY, "Open Agent Gravity")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, base.usableSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, fileForDocId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ctx = context
            if (ctx != null) {
                val launchIntent = Intent(ctx, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                val pendingIntent = PendingIntent.getActivity(
                    ctx,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                throw AuthenticationRequiredException(
                    SecurityException("Open Agent Gravity"),
                    pendingIntent
                )
            }
        }

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { child ->
            includeFile(result, docIdForFile(child), child)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun getDocumentType(documentId: String): String {
        val file = fileForDocId(documentId)
        return mimeTypeForFile(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = fileForDocId(parentDocumentId).canonicalFile
            val child = fileForDocId(documentId).canonicalFile
            child == parent || child.path.startsWith(parent.path + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun includeFile(result: MatrixCursor, docId: String, file: File) {
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, if (docId == ROOT_ID) "Agent Gravity" else file.name)
            add(Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeTypeForFile(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun mimeTypeForFile(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
