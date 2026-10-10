package com.ryose.videoplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** 設定画面で変更できる設定（キーは res/xml/preferences.xml と同じ） */
object AppSettings {

    private fun prefs(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)

    private fun bool(c: Context, key: String, def: Boolean) = prefs(c).getBoolean(key, def)

    private fun string(c: Context, key: String, def: String) = prefs(c).getString(key, def) ?: def

    /** VLC のエンジンに必ず渡すオプション */
    val BASE_VLC_OPTIONS = listOf(
        "--audio-time-stretch",     // 速度を変えても声の高さを変えない
        "--no-sub-autodetect-file", // 字幕の自動読み込みはアプリ側で行う（文字コード変換のため）
        "--http-reconnect",
    )

    /** 字幕の見た目などを含めた、VLC のエンジンに渡すオプション */
    fun vlcOptions(c: Context): List<String> = buildList {
        addAll(BASE_VLC_OPTIONS)
        add("--deinterlace=${string(c, "deinterlace", "-1")}")
        add("--deinterlace-mode=blend")
        add("--freetype-rel-fontsize=${string(c, "sub_size", "16")}")
        add("--freetype-color=${string(c, "sub_color", "16777215")}")
        add("--freetype-outline-thickness=${string(c, "sub_outline", "4")}")
        if (bool(c, "sub_bold", false)) add("--freetype-bold")
        if (bool(c, "sub_background", false)) {
            add("--freetype-background-opacity=128")
            add("--freetype-background-color=0")
        } else {
            add("--freetype-background-opacity=0")
        }
    }

    // 再生
    fun resume(c: Context) = bool(c, "resume", true)
    fun hwDecoding(c: Context) = bool(c, "hw_decoding", true)
    fun audioBoost(c: Context) = bool(c, "audio_boost", true)
    /** 再生画面の向き："auto"（スマホの向きに合わせて自動回転）・"user"（回転ロックに従う）・"video"（動画に合わせる） */
    fun playerOrientation(c: Context) = string(c, "player_orientation", "auto")
    /** 「動画」タブの一覧から再生したとき、終わったら次の動画を再生する */
    fun videosAutoNext(c: Context) = bool(c, "videos_auto_next", false)
    fun networkCachingMs(c: Context) = string(c, "network_caching", "1500").toIntOrNull() ?: 1500

    /** テーマ（"dark"・"light"・"system"）を反映する */
    fun applyTheme(c: Context, value: String = string(c, "theme", "dark")) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "system" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    /** 動画の画面を離れたとき："POPUP"（自由な小窓）・"PIP"・"AUDIO"・"PAUSE" */
    fun leaveAction(c: Context): String {
        val p = prefs(c)
        if (p.contains("leave_action")) return p.getString("leave_action", "POPUP") ?: "POPUP"
        // 以前のバージョンで保存した値を引き継ぐ
        return c.getSharedPreferences("player", Context.MODE_PRIVATE).getString("leave_action", null) ?: "POPUP"
    }

    fun setLeaveAction(c: Context, v: String) = prefs(c).edit().putString("leave_action", v).apply()

    // ジェスチャー
    fun gestureBrightness(c: Context) = bool(c, "gesture_brightness", true)
    fun gestureVolume(c: Context) = bool(c, "gesture_volume", true)
    fun gestureSeek(c: Context) = bool(c, "gesture_seek", true)
    fun gestureDoubleTap(c: Context) = bool(c, "gesture_double_tap", true)
    fun gestureLongPress(c: Context) = bool(c, "gesture_long_press", true)
    fun doubleTapMs(c: Context) = (string(c, "double_tap_seconds", "10").toIntOrNull() ?: 10) * 1000L

    // 自由な小窓の大きさ（長い方の辺、dp）と位置（px）。前回の場所に出す
    fun popupSizeDp(c: Context) = prefs(c).getInt("popup_size", PopupGeometry.DEFAULT_SIZE_DP)
    fun popupPosition(c: Context): Pair<Int, Int>? = prefs(c).takeIf { it.contains("popup_x") }
        ?.let { it.getInt("popup_x", 0) to it.getInt("popup_y", 0) }
    fun setPopupPlacement(c: Context, sizeDp: Int, x: Int, y: Int) =
        prefs(c).edit().putInt("popup_size", sizeDp).putInt("popup_x", x).putInt("popup_y", y).apply()

    /** 自由な小窓の許可を、再生画面で一度お願いした */
    fun popupPermissionAsked(c: Context) = bool(c, "popup_permission_asked", false)
    fun setPopupPermissionAsked(c: Context) = prefs(c).edit().putBoolean("popup_permission_asked", true).apply()

    // 一覧
    fun videosGrid(c: Context) = bool(c, "videos_grid", false)
    fun setVideosGrid(c: Context, v: Boolean) = prefs(c).edit().putBoolean("videos_grid", v).apply()
    fun videosByFolder(c: Context) = bool(c, "videos_by_folder", false)
    fun setVideosByFolder(c: Context, v: Boolean) = prefs(c).edit().putBoolean("videos_by_folder", v).apply()
    /** 動画タブの並び順（"DATE"・"NAME"・"DURATION"） */
    fun videosSort(c: Context) = string(c, "videos_sort", "DATE")
    fun setVideosSort(c: Context, v: String) = prefs(c).edit().putString("videos_sort", v).apply()
    /** 音楽タブの表示（"SONGS"・"ALBUMS"・"ARTISTS"） */
    fun musicMode(c: Context) = string(c, "music_mode", "SONGS")
    fun setMusicMode(c: Context, v: String) = prefs(c).edit().putString("music_mode", v).apply()
}
