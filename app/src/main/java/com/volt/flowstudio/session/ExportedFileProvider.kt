package com.volt.flowstudio.session

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 只为导出文件分享服务的 ContentProvider。
 * 不用 androidx 的 FileProvider，省掉一个依赖；逻辑其实很小。
 */
class ExportedFileProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY_SUFFIX = ".exports"

        fun authority(packageName: String) = "$packageName$AUTHORITY_SUFFIX"

        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context.packageName))
                .appendPath(file.name)
                .build()

        private fun dirOf(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val file = File(dirOf(ctx), name)
        if (!file.isFile) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(cols.size)
        cols.forEachIndexed { i, c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row[i] = file.name
                OpenableColumns.SIZE -> row[i] = file.length()
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = "audio/wav"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("无上下文")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("无文件名")
        // 只允许访问导出目录里真实存在的文件，挡住路径穿越
        if (name.contains('/') || name.contains("..")) throw FileNotFoundException("非法路径")
        val file = File(dirOf(ctx), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
