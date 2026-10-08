package com.ryose.videoplayer

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import com.google.android.material.progressindicator.LinearProgressIndicator

class VideoAdapter(
    private val resume: ResumeStore,
    private val onClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    var items: List<Video> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val duration: TextView = view.findViewById(R.id.duration)
        val progress: LinearProgressIndicator = view.findViewById(R.id.progress)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = items[position]
        val ctx = holder.itemView.context
        holder.title.text = v.title
        holder.meta.text = "${v.folder} · ${Formatter.formatShortFileSize(ctx, v.size)}"
        holder.duration.text = formatTime(v.durationMs)
        holder.duration.visibility = if (v.durationMs > 0) View.VISIBLE else View.GONE
        holder.thumb.load(v.uri) {
            videoFrameMillis(1000)
            placeholder(R.drawable.ic_movie)
            error(R.drawable.ic_movie)
        }

        val pos = resume.get(v.uri.toString())
        if (pos > 0 && v.durationMs > 0) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = (pos * 1000 / v.durationMs).toInt()
        } else {
            holder.progress.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}
