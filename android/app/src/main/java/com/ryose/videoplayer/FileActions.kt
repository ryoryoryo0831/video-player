package com.ryose.videoplayer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** 動画・音楽ファイルの共有・詳細・名前の変更・削除 */
object FileActions {

    /** 端末内のファイルか（ネットワーク上のものは操作できない） */
    fun isLocal(item: PlaylistItem) = !item.isNetwork

    private fun isMediaStoreUri(uri: Uri) =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY

    /** 名前の変更はファイルを直接書き換えるので「すべてのファイルへのアクセス」が必要 */
    fun canRename(context: Context, item: PlaylistItem) =
        item.path != null && context.hasStorageAccess() && File(item.path).parentFile?.canWrite() == true

    fun canDelete(context: Context, item: PlaylistItem) =
        canRename(context, item) || (Build.VERSION.SDK_INT >= 30 && isMediaStoreUri(item.uri))

    fun share(context: Context, item: PlaylistItem) {
        val uri = when {
            isMediaStoreUri(item.uri) -> item.uri
            item.path != null -> runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", File(item.path))
            }.getOrNull()
            else -> item.uri
        } ?: run {
            Toast.makeText(context, "このファイルは共有できません", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (item.isAudio) "audio/*" else "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, item.title)) }
    }

    /** ファイルの詳細（場所・サイズ・長さ・解像度・更新日時） */
    suspend fun showDetails(context: Context, item: PlaylistItem) {
        val lines = withContext(Dispatchers.IO) {
            val file = item.path?.let { File(it) }
            val r = MediaMetadataRetriever()
            val meta = try {
                if (file != null && file.canRead()) r.setDataSource(file.path) else r.setDataSource(context, item.uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                listOfNotNull(
                    duration?.let { "長さ：${formatTime(it)}" },
                    if (w != null && h != null) "解像度：$w × $h" else null,
                    bitrate?.let { "ビットレート：${it / 1000} kbps" },
                )
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { r.release() }
            }
            listOfNotNull(
                "名前：${file?.name ?: item.title}",
                file?.parent?.let { "場所：$it" },
                file?.takeIf { it.exists() }?.let { "サイズ：${Formatter.formatFileSize(context, it.length())}" },
                file?.takeIf { it.exists() }?.let {
                    "更新日時：" + DateFormat.getMediumDateFormat(context).format(Date(it.lastModified())) + " " +
                        DateFormat.getTimeFormat(context).format(Date(it.lastModified()))
                },
            ) + meta
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("詳細")
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("閉じる", null)
            .show()
    }

    /** 名前を変更する（同じ名前の字幕ファイルも一緒に変える） */
    fun rename(context: Context, item: PlaylistItem, onDone: () -> Unit) {
        val file = File(item.path ?: return)
        val dp = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            setText(file.nameWithoutExtension)
            setSingleLine()
            selectAll()
        }
        val box = FrameLayout(context).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("名前を変更")
            .setView(box)
            .setPositiveButton("変更") { _, _ ->
                val newBase = input.text.toString().trim()
                if (newBase.isEmpty() || newBase.any { it in "\\/:*?\"<>|" }) {
                    Toast.makeText(context, "使えない文字が含まれています", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ext = if (file.extension.isEmpty()) "" else ".${file.extension}"
                val target = File(file.parentFile, newBase + ext)
                if (target.exists()) {
                    Toast.makeText(context, "同じ名前のファイルがあります", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!file.renameTo(target)) {
                    Toast.makeText(context, "名前を変更できませんでした", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val changed = mutableListOf(file.path, target.path)
                // movie.srt・movie.ja.srt なども新しい名前に合わせる
                Subtitles.findFor(file.path).forEach { sub ->
                    val rest = sub.name.substring(file.nameWithoutExtension.length)
                    val newSub = File(sub.parentFile, newBase + rest)
                    if (!newSub.exists() && sub.renameTo(newSub)) changed += listOf(sub.path, newSub.path)
                }
                ResumeStore(context).migrate(file.path, target.path)
                // プレイリスト・お気に入り・履歴も新しい名前に合わせる（古い名前のままだと開けなくなる）
                val renamed = PlaylistItem.fromFile(target)
                PlaylistStore(context).replaceEverywhere(item.key, renamed)
                HistoryStore(context).replace(item.key, renamed)
                MediaScannerConnection.scanFile(context.applicationContext, changed.toTypedArray(), null, null)
                onDone()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * 削除する。ファイルを直接消せる場合は確認のうえ消し、
     * 「すべてのファイルへのアクセス」が無い場合は Android の確認画面（launchSystemDelete）で消す
     */
    fun delete(context: Context, item: PlaylistItem, launchSystemDelete: (IntentSenderRequest) -> Unit, onDone: () -> Unit) {
        if (canRename(context, item)) {
            MaterialAlertDialogBuilder(context)
                .setTitle("削除")
                .setMessage("「${item.title}」を端末から削除しますか？\n元に戻せません。")
                .setPositiveButton("削除") { _, _ ->
                    val file = File(item.path!!)
                    if (file.delete()) {
                        forget(context, item)
                        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), null, null)
                        onDone()
                    } else {
                        Toast.makeText(context, "削除できませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else if (Build.VERSION.SDK_INT >= 30 && isMediaStoreUri(item.uri)) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
            launchSystemDelete(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    /** 削除したファイルの履歴・再生位置・プレイリスト（お気に入りも）の記録を消す */
    fun forget(context: Context, item: PlaylistItem) {
        ResumeStore(context).clear(item.key)
        HistoryStore(context).remove(item.key)
        PlaylistStore(context).replaceEverywhere(item.key, null)
    }
}
