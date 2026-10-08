package com.ryose.videoplayer

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PlaylistDialogs {

    /** 「プレイリストに追加」：既存のプレイリストを選ぶか、新しく作る */
    fun addToPlaylist(context: Context, items: List<PlaylistItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) {
            Toast.makeText(context, "追加できる動画がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val store = PlaylistStore(context)
        val lists = store.all()
        val labels = lists.map { "${it.name}（${it.items.size}本）" } + "＋  新しいプレイリスト"

        fun addTo(p: SavedPlaylist) {
            val added = store.addItems(p.id, items)
            val msg = if (added == 0) "「${p.name}」にはすでに入っています" else "「${p.name}」に${added}本追加しました"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onDone()
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("プレイリストに追加")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < lists.size) addTo(lists[i])
                else promptName(context, "新しいプレイリスト", "") { name -> addTo(store.create(name)) }
            }
            .show()
    }

    /** 名前を入力するダイアログ */
    fun promptName(context: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            hint = "プレイリストの名前"
        }
        val box = FrameLayout(context).apply {
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("キャンセル", null)
            .show()
        input.requestFocus()
    }

    fun confirm(context: Context, message: String, okLabel: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setMessage(message)
            .setPositiveButton(okLabel) { _, _ -> onOk() }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
