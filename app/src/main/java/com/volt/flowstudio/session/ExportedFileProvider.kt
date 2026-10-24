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

        /**
         * 可能存放导出文件的所有目录。
         *
         * 导出时会按 公共 Music → 外部私有 → 内部私有 逐级回退，
         * 所以这里必须把三处都算上。之前只写了 exports 一处，
         * 结果文件明明在 Music/FlowStudio 里，分享却报"文件不存在"。
         */
        fun candidateDirs(context: Context): List<File> = listOfNotNull(
            runCatching {
                File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC
                    ),
                    "FlowStudio"
                )
            }.getOrNull(),
            runCatching { File(context.getExternalFilesDir(null), "exports") }.getOrNull(),
            File(context.filesDir, "exports")
        )

        /** 在所有候选目录里找这个文件名 */
        fun resolve(context: Context, name: String): File? {
            // 挡路径穿越：只接受纯文件名
            if (name.isEmpty() || name.contains('/') || name.contains("\\") || name.contains("..")) {
                return null
            }
            for (dir in candidateDirs(context)) {
                val f = File(dir, name)
                if (f.isFile) return f
            }
            return null
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val file = resolve(ctx, name) ?: return null
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
        // resolve 内部会挡路径穿越，并且覆盖三个候选目录
        val file = resolve(ctx, name) ?: throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
