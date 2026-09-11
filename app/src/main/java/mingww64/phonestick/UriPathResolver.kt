package mingww64.phonestick

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

object UriPathResolver {
    private const val TAG = "UriPathResolver"

    fun getRealPathFromUri(context: Context, uri: Uri): String {
        val path = getRealPathFromUriInternal(context, uri)
        if (path.startsWith("/storage/emulated/")) {
            val passThroughPath = path.replaceFirst("/storage/emulated/", "/mnt/pass_through/0/emulated/")
            if (checkExistsViaRoot(passThroughPath)) {
                Log.d(TAG, "Rewrote FUSE path to pass_through path: $passThroughPath")
                return passThroughPath
            }
            val rawPath = path.replaceFirst("/storage/emulated/", "/data/media/")
            if (checkExistsViaRoot(rawPath)) {
                Log.d(TAG, "Rewrote FUSE path to raw path: $rawPath")
                return rawPath
            }
        }
        return path
    }

    private fun getRealPathFromUriInternal(context: Context, uri: Uri): String {
        Log.d(TAG, "Resolving URI: $uri")

        // 1. Direct file scheme
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrEmpty() && checkExistsViaRoot(path)) {
                return path
            }
        }

        // 2. DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)

            // ExternalStorageProvider
            if ("com.android.externalstorage.documents" == uri.authority) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]

                    if ("primary".equals(type, ignoreCase = true)) {
                        // Bypass FUSE by using the underlying raw Ext4 path
                        val rawDataPath = "/data/media/0/$relativePath"
                        if (checkExistsViaRoot(rawDataPath)) return rawDataPath
                        
                        val path = "${Environment.getExternalStorageDirectory()}/$relativePath"
                        if (checkExistsViaRoot(path)) return path
                        
                        val altPath = "/storage/emulated/0/$relativePath"
                        if (checkExistsViaRoot(altPath)) return altPath
                    } else {
                        val path = "/storage/$type/$relativePath"
                        if (checkExistsViaRoot(path)) return path
                        val mediaRwPath = "/mnt/media_rw/$type/$relativePath"
                        if (checkExistsViaRoot(mediaRwPath)) return mediaRwPath
                    }
                }
            }
            // DownloadsProvider
            else if ("com.android.providers.downloads.documents" == uri.authority) {
                if (docId.startsWith("raw:")) {
                    val rawPath = docId.substring(4)
                    if (checkExistsViaRoot(rawPath)) return rawPath
                }
                try {
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"),
                        docId.toLongOrNull() ?: 0L
                    )
                    val path = getDataColumn(context, contentUri, null, null)
                    if (!path.isNullOrEmpty() && checkExistsViaRoot(path)) return path
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve downloads provider URI: ${e.message}")
                }
            }
            // MediaProvider
            else if ("com.android.providers.media.documents" == uri.authority) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]
                    val contentUri = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(id)
                    val path = getDataColumn(context, contentUri, selection, selectionArgs)
                    if (!path.isNullOrEmpty() && checkExistsViaRoot(path)) return path
                }
            }
        }
        // Generic content scheme query
        else if ("content".equals(uri.scheme, ignoreCase = true)) {
            val path = getDataColumn(context, uri, null, null)
            if (!path.isNullOrEmpty() && checkExistsViaRoot(path)) return path
        }

        // 3. Fallback: Copy to app internal storage cache if direct filesystem path not readable by kernel
        return copyUriToAppCache(context, uri)
    }

    private fun checkExistsViaRoot(path: String): Boolean {
        if (path.isEmpty()) return false
        val escapedPath = path.replace("'", "'\\''")
        try {
            val res = com.topjohnwu.superuser.Shell.cmd("if [ -e '$escapedPath' ]; then echo EXISTS; fi").exec()
            val exists = res.out.contains("EXISTS")
            Log.d(TAG, "checkExistsViaRoot for $path: $exists (out: ${res.out}, err: ${res.err})")
            return exists
        } catch (e: Exception) {
            Log.e(TAG, "checkExistsViaRoot failed for $path", e)
            return false
        }
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(column)
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDataColumn failed: ${e.message}")
        }
        return null
    }

    private fun copyUriToAppCache(context: Context, uri: Uri): String {
        try {
            val decoded = Uri.decode(uri.toString())
            var fileName = decoded.substringAfterLast('/').substringAfterLast("%3F").substringAfterLast('?')
            if (fileName.contains(':')) fileName = fileName.substringAfterLast(':')
            if (!fileName.endsWith(".img", true) && !fileName.endsWith(".iso", true)) {
                fileName = "imported_$fileName.img"
            }

            val targetFile = File(context.filesDir, fileName)
            val tempFile = File(context.filesDir, "$fileName.tmp")

            Log.i(TAG, "Copying SAF URI to internal storage: ${targetFile.absolutePath}")

            // Write to a temp file first so we never leave a 0-byte stub if the copy fails
            tempFile.delete() // clean up any previous failed attempt
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                // Atomic rename: replace target with the fully-written temp file
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    return targetFile.absolutePath
                }
                // renameTo failed (e.g. cross-device) — fall back to copy + delete
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                if (targetFile.exists() && targetFile.length() > 0) {
                    return targetFile.absolutePath
                }
            } else {
                // Stream was empty or openInputStream returned null
                tempFile.delete()
                Log.e(TAG, "SAF URI copy produced empty file, aborting")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy SAF URI: ${e.message}", e)
            // Clean up any partial temp file
            File(context.filesDir, "${Uri.decode(uri.toString()).substringAfterLast('/')}.tmp").delete()
        }
        return uri.toString()
    }
}

