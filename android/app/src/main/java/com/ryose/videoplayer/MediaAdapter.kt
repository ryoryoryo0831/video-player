package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
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

    override fun getItemViewType(position: Int) = if (data[position] is Row.Folder) 0 else 1

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
        else MediaHolder(inflater.inflate(R.layout.item_video, parent, false))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
        when (val row = data[position]) {
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
                holder.meta.text = row.meta
                holder.meta.visibility = if (row.meta.isEmpty()) View.GONE else View.VISIBLE
                holder.duration.text = formatTime(item.durationMs)
                holder.duration.visibility = if (item.durationMs > 0) View.VISIBLE else View.GONE
                holder.thumb.load(item.path?.let { File(it) } ?: item.uri) {
                    videoFrameMillis(1000)
                    placeholder(R.drawable.ic_movie)
                    error(R.drawable.ic_movie)
                }

                val pos = resume.get(item.key)
                if (pos > 0 && item.durationMs > 0) {
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.progress = (pos * 1000 / item.durationMs).toInt().coerceIn(0, 1000)
                } else {
                    holder.progress.visibility = View.GONE
                }

                val drag = dragListener
                holder.dragHandle.visibility = if (drag != null) View.VISIBLE else View.GONE
                holder.dragHandle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) drag?.invoke(holder)
                    false
                }
            }
        }
    }
}
