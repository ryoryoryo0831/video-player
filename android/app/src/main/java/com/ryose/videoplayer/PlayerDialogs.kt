package com.ryose.videoplayer

import android.content.Context
import com.google.android.material.color.MaterialColors
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.videolan.libvlc.MediaPlayer
import kotlin.math.roundToInt

/** イコライザーの設定（アプリ内に保存） */
data class EqSettings(val enabled: Boolean, val preset: Int, val preamp: Float, val bands: List<Float>)

object EqualizerPrefs {
    private const val NAME = "equalizer"

    fun load(context: Context): EqSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val count = MediaPlayer.Equalizer.getBandCount()
        val saved = p.getString("bands", "").orEmpty().split(",").mapNotNull { it.toFloatOrNull() }
        return EqSettings(
            enabled = p.getBoolean("enabled", false),
            preset = p.getInt("preset", -1),
            preamp = p.getFloat("preamp", 0f),
            bands = if (saved.size == count) saved else List(count) { 0f },
        )
    }

    fun save(context: Context, s: EqSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putInt("preset", s.preset)
            .putFloat("preamp", s.preamp)
            .putString("bands", s.bands.joinToString(","))
            .apply()
    }
}

/** 動画・音楽の再生画面で共通のダイアログ */
object PlayerDialogs {


    private fun mark(on: Boolean) = if (on) "✓  " else "      "

    /** 再生速度：スライダーで 0.25〜4 倍を 0.05 刻み、よく使う速度はボタンで */
    fun showSpeed(context: Context, svc: PlaybackService, onChanged: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        val value = TextView(context).apply {
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val slider = com.google.android.material.slider.Slider(context).apply {
            valueFrom = 0.25f
            valueTo = 4f
            stepSize = 0.05f
            value = (Math.round(svc.rate * 20) / 20f).coerceIn(0.25f, 4f)
            setLabelFormatter { formatRate(it) }
        }
        fun apply(r: Float) {
            svc.setPlaybackRate(r)
            value.text = formatRate(r)
            onChanged()
        }
        value.text = formatRate(svc.rate)
        slider.addOnChangeListener { _, v, fromUser -> if (fromUser) apply(v) }
        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
            presets.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = if (r == 1f) "標準" else formatRate(r)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        slider.value = r
                        apply(r)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (2 * dp).toInt()
                    marginEnd = (2 * dp).toInt()
                },
            )
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(value)
            addView(slider)
            addView(presets)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("再生速度")
            .setView(box)
            .setPositiveButton("閉じる", null)
            .show()
    }

    fun sleepLabel(svc: PlaybackService): String? = when {
        svc.sleepAtEnd -> "この曲・動画の終わりで停止"
        svc.sleepAt > 0 -> {
            val min = ((svc.sleepAt - System.currentTimeMillis()) / 60_000.0).let { kotlin.math.ceil(it).toInt() }
            "あと ${min.coerceAtLeast(1)} 分で停止"
        }
        else -> null
    }

    fun showSleepTimer(context: Context, svc: PlaybackService) {
        val minutes = listOf(15, 30, 45, 60, 90, 120)
        val labels = listOf("オフ") + minutes.map { "$it 分後" } + "この曲・動画の終わりまで"
        MaterialAlertDialogBuilder(context)
            .setTitle(sleepLabel(svc)?.let { "スリープタイマー（$it）" } ?: "スリープタイマー")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> svc.cancelSleepTimer()
                    labels.lastIndex -> svc.setSleepAtEnd()
                    else -> svc.setSleepTimer(minutes[which - 1])
                }
            }
            .show()
    }

    /** キャスト：同じネットワークの Chromecast などを探して、そこで再生する */
    fun showCast(context: Context, svc: PlaybackService) {
        svc.startRendererDiscovery()
        val adapter = android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
        var targets: List<org.videolan.libvlc.RendererItem?> = emptyList()

        fun refresh() {
            val list = mutableListOf<org.videolan.libvlc.RendererItem?>()
            val labels = mutableListOf<String>()
            if (svc.renderer != null) {
                list += null
                labels += "📱  この端末で再生"
            }
            svc.renderers.values.forEach {
                list += it
                labels += (if (it == svc.renderer) "✓  " else "📺  ") + (it.displayName ?: it.name)
            }
            targets = list
            adapter.clear()
            adapter.addAll(labels)
            // 探している間の案内（タップしても何もしない行）
            if (svc.renderers.isEmpty()) adapter.add("探しています…\n同じ Wi-Fi の Chromecast などがここに出ます")
        }

        val listener = object : PlaybackService.Listener {
            override fun onRenderersChanged() = refresh()
        }
        svc.addListener(listener)
        refresh()
        MaterialAlertDialogBuilder(context)
            .setTitle("キャスト（テレビ・スピーカーで再生）")
            .setAdapter(adapter) { _, which ->
                val target = targets.getOrNull(which) ?: if (which < targets.size) null else return@setAdapter
                svc.castTo(target)
                val msg = if (target == null) "この端末での再生に戻しました" else "${target.displayName ?: target.name} で再生します"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("閉じる", null)
            .setOnDismissListener {
                svc.removeListener(listener)
                svc.stopRendererDiscovery()
            }
            .show()
    }

    /** 再生キュー：これから再生する順番の一覧。タップでその曲へ */
    fun showQueue(context: Context, svc: PlaybackService) {
        val order = svc.order
        if (order.isEmpty()) return
        val labels = order.mapIndexed { pos, i ->
            (if (pos == svc.orderPos) "▶  " else "      ") + svc.items[i].title
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("再生キュー（${order.size}）")
            .setItems(labels.toTypedArray()) { _, pos -> if (pos != svc.orderPos) svc.playAt(pos) }
            .create()
        dialog.show()
        // 今の曲が見える位置までスクロール
        dialog.listView?.setSelection((svc.orderPos - 2).coerceAtLeast(0))
    }

    /** イコライザー（VLC と同じプリセット・10バンド） */
    fun showEqualizer(context: Context, svc: PlaybackService) {
        val dp = context.resources.displayMetrics.density
        // 動画の画面（いつも暗い）と音楽の画面（明るいこともある）の両方で読めるよう、画面のテーマの色を使う
        val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val bandCount = MediaPlayer.Equalizer.getBandCount()
        var s = EqualizerPrefs.load(context)

        fun presetName(i: Int) = if (i < 0) "カスタム" else MediaPlayer.Equalizer.getPresetName(i)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val enable = MaterialSwitch(context).apply {
            text = "イコライザーを使う"
            isChecked = s.enabled
        }
        root.addView(enable)

        val presetButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        root.addView(presetButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // スライダーは 0.1dB 単位、-20〜+20dB
        fun toProgress(db: Float) = ((db + 20f) * 10).roundToInt()
        fun toDb(progress: Int) = progress / 10f - 20f
        fun fmtDb(db: Float) = "%+.1f dB".format(db)

        val sliders = mutableListOf<Pair<SeekBar, TextView>>()
        fun addSlider(label: String, value: Float, onChange: (Float) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(context).apply {
                text = label
                setTextColor(textColor)
                textSize = 13f
                minWidth = (64 * dp).toInt()
            }
            val valueText = TextView(context).apply {
                text = fmtDb(value)
                setTextColor(muted)
                textSize = 12f
                minWidth = (64 * dp).toInt()
                gravity = Gravity.END
            }
            val bar = SeekBar(context).apply {
                max = 400
                progress = toProgress(value)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        valueText.text = fmtDb(toDb(p))
                        if (fromUser) onChange(toDb(p))
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(name)
            row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueText)
            root.addView(row)
            sliders += bar to valueText
        }

        fun applyAndSave(new: EqSettings) {
            s = new
            EqualizerPrefs.save(context, s)
            svc.applyEqualizer()
            presetButton.text = "プリセット：${presetName(s.preset)}"
        }

        addSlider("プリアンプ", s.preamp) { db -> applyAndSave(s.copy(preamp = db, enabled = true).also { enable.isChecked = true }) }
        for (i in 0 until bandCount) {
            val f = MediaPlayer.Equalizer.getBandFrequency(i)
            val label = if (f < 1000) "${f.roundToInt()} Hz" else "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} kHz"
            addSlider(label, s.bands.getOrElse(i) { 0f }) { db ->
                val bands = s.bands.toMutableList().also { it[i] = db }
                applyAndSave(s.copy(bands = bands, preset = -1, enabled = true).also { enable.isChecked = true })
            }
        }

        fun refreshSliders() {
            sliders[0].first.progress = toProgress(s.preamp)
            s.bands.forEachIndexed { i, v -> sliders.getOrNull(i + 1)?.first?.progress = toProgress(v) }
        }

        presetButton.text = "プリセット：${presetName(s.preset)}"
        presetButton.setOnClickListener {
            val count = MediaPlayer.Equalizer.getPresetCount()
            val names = (0 until count).map { mark(it == s.preset) + MediaPlayer.Equalizer.getPresetName(it) }
            MaterialAlertDialogBuilder(context)
                .setTitle("プリセット")
                .setItems(names.toTypedArray()) { _, which ->
                    val eq = MediaPlayer.Equalizer.createFromPreset(which)
                    applyAndSave(
                        EqSettings(
                            enabled = true,
                            preset = which,
                            preamp = eq.preAmp,
                            bands = (0 until bandCount).map { eq.getAmp(it) },
                        )
                    )
                    enable.isChecked = true
                    refreshSliders()
                }
                .show()
        }
        enable.setOnCheckedChangeListener { _, checked ->
            if (checked != s.enabled) applyAndSave(s.copy(enabled = checked))
        }

        val scroll = ScrollView(context).apply { addView(root) }
        MaterialAlertDialogBuilder(context)
            .setTitle("イコライザー")
            .setView(scroll)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("リセット") { _, _ ->
                applyAndSave(EqSettings(false, -1, 0f, List(bandCount) { 0f }))
            }
            .show()
    }
}
