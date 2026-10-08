package com.ryose.videoplayer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** スクリーンショットを「写真」アプリから見える場所に保存する */
object Screenshots {

    private const val FOLDER = "VideoPlayer"

    /** 保存した場所（表示用）を返す。失敗したら null */
    fun save(context: Context, bitmap: Bitmap, baseName: String): String? = try {
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val fileName = "$safe.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存先を作れません")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "ピクチャ/$FOLDER/$fileName"
        } else {
            // Android 9 以前はアプリ専用のフォルダに保存する（追加の許可が要らないため）
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.path
        }
    } catch (_: Exception) {
        null
    } finally {
        bitmap.recycle()
    }
}
