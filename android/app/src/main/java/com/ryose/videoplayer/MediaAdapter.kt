package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** 一覧の1行 */
sealed class Row {
    /** フォルダ・ストレージ・プレイリストなど、中に入っていく行 */
    data class Folder(
        val name: String,
        val info: String,
        val icon: Int = R.drawable.ic_folder,
        val dir: File? = null,
        val id: String? = null,
        val isStorage: Boolean = false,
    ) : Row()

    /** 動画の行 */
    data class Media(val item: PlaylistItem, val meta: String) : Row()

    /** 見出し（「ストレージ」「ネットワーク」など） */
    data class Header(val title: String) : Row()
}

class MediaAdapter(
    private val resume: ResumeStore,
    private val onClick: (position: Int) -> Unit,
    private val onLongClick: (position: Int) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var data = mutableListOf<Row>()

    var rows: List<Row>
        get() = data
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            data = value.toMutableList()
            notifyDataSetChanged()
        }

    /** グリッド表示（動画の行を大きなサムネイルのマスで表示する） */
    var grid = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** お気に入りの動画・曲（行に ★ を付ける） */
    var favoriteKeys: Set<String> = emptySet()

    /** 設定するとつまみを表示し、触ったときに呼ばれる（プレイリストの並べ替え用） */
    var dragListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun move(from: Int, to: Int) {
        if (from !in data.indices || to !in data.indices) return
        data.add(to, data.removeAt(from))
        notifyItemMoved(from, to)
    }

    class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
    }

    class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val duration: TextView = view.findViewById(R.id.duration)
        val progress: LinearProgressIndicator = view.findViewById(R.id.progress)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header)
    }

    override fun getItemViewType(position: Int) = when (data[position]) {
        is Row.Folder -> 0
        is Row.Media -> if (grid) 3 else 1
        is Row.Header -> 2
    }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
            2 -> HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
            3 -> MediaHolder(inflater.inflate(R.layout.item_video_grid, parent, false))
            else -> MediaHolder(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    /** 視聴位置のバーだけを更新する（サムネイルなどは読み込み直さない） */
    fun refreshProgress() = notifyItemRangeChanged(0, itemCount, PAYLOAD_PROGRESS)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = data.getOrNull(position)
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
            if (holder is MediaHolder && row is Row.Media) bindProgress(holder, row.item)
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindProgress(holder: MediaHolder, item: PlaylistItem) {
        val pos = resume.get(item.key)
        if (pos > 0 && item.durationMs > 0) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = (pos * 1000 / item.durationMs).toInt().coerceIn(0, 1000)
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
        when (val row = data[position]) {
            is Row.Header -> {
                (holder as HeaderHolder).title.text = row.title
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
            }
            is Row.Folder -> {
                holder as FolderHolder
                holder.icon.setImageResource(row.icon)
                holder.name.text = row.name
                holder.info.text = row.info
                holder.info.visibility = if (row.info.isEmpty()) View.GONE else View.VISIBLE
            }
            is Row.Media -> {
                holder as MediaHolder
                val item = row.item
                holder.title.text = item.title
                val fav = item.key in favoriteKeys
                holder.meta.text = if (fav) {
                    // お気に入りは先頭に色付きの ★
                    android.text.SpannableString("★ " + row.meta).apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.accent)),
                            0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } else row.meta
                holder.meta.visibility = if (row.meta.isEmpty() && !fav) View.GONE else View.VISIBLE
                holder.duration.text = formatTime(item.durationMs)
                holder.duration.visibility = if (item.durationMs > 0) View.VISIBLE else View.GONE
                if (item.isNetwork) {
                    // ネットワーク上のファイルはサムネイルを作らない（全体を読み込んでしまうため）
                    holder.thumb.dispose()
                    holder.thumb.setImageResource(if (item.isAudio) R.drawable.ic_music_note else R.drawable.ic_movie)
                } else if (item.isAudio) {
                    // 音楽はファイルに埋め込まれたジャケット画像
                    holder.thumb.load(item.path?.let { AudioArt(it) }) {
                        placeholder(R.drawable.ic_music_note)
                        error(R.drawable.ic_music_note)
                        fallback(R.drawable.ic_music_note)
                    }
                } else {
                    holder.thumb.load(VideoThumb(item.path, item.uri)) {
                        placeholder(R.drawable.ic_movie)
                        error(R.drawable.ic_movie)
                    }
                }

                bindProgress(holder, item)

                val drag = dragListener
                holder.dragHandle.visibility = if (drag != null) View.VISIBLE else View.GONE
                holder.dragHandle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) drag?.invoke(holder)
                    false
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"
    }
}
