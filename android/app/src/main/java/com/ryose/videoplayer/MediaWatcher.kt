package com.ryose.videoplayer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 端末の動画・音楽が増えた・消えたことを知らせる。
 * 表示中なら少し待ってから onChanged を呼び、表示していない間に変わった場合は次に表示したときに呼ぶ
 */
class MediaWatcher(
    private val context: Context,
    private val collection: Uri,
    private val onChanged: () -> Unit,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var dirty = false
    private val notify = Runnable {
        if (resumed) onChanged() else dirty = true
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // コピー中などは続けて届くので、落ち着いてから 1 回だけ読み込み直す
            handler.removeCallbacks(notify)
            handler.postDelayed(notify, 1500)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        runCatching { context.contentResolver.registerContentObserver(collection, true, observer) }
    }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        if (dirty) {
            dirty = false
            onChanged()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(notify)
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}
