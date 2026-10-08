package com.ryose.videoplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * 画面から再生サービスにつなぐための小さな部品。
 * autoCreate = false の場合は、サービスが動いているときだけつながる（ミニプレイヤー用）。
 */
class PlaybackConnection(
    private val context: Context,
    private val autoCreate: Boolean,
    private val onConnected: (PlaybackService) -> Unit,
    private val onDisconnected: () -> Unit = {},
) : ServiceConnection {

    var service: PlaybackService? = null
        private set
    private var bound = false

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, PlaybackService::class.java), this,
            if (autoCreate) Context.BIND_AUTO_CREATE else 0,
        )
    }

    fun unbind() {
        if (bound) {
            runCatching { context.unbindService(this) }
            bound = false
        }
        if (service != null) {
            service = null
            onDisconnected()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val s = (binder as? PlaybackService.LocalBinder)?.service ?: return
        service = s
        onConnected(s)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        onDisconnected()
    }
}
