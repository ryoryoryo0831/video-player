package com.ryose.videoplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 再生サービスが止まっている間に押された、イヤホン・Bluetooth の再生ボタンを受け取る。
 * 前回の再生キューの続きから再生する（再生中は MediaSession が直接ボタンを受け取るので、ここには来ない）
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return
        val action = when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> PlaybackService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackService.ACTION_PREVIOUS
            else -> return
        }
        // 端末の制限で起こせない場合もあるので、失敗しても落ちないようにする
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
                    .setAction(action)
                    .putExtra(PlaybackService.EXTRA_FOREGROUND, true),
            )
        }
    }
}
