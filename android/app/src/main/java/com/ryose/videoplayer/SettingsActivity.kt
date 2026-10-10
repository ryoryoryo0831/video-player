package com.ryose.videoplayer

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 設定画面 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "設定"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // 横向きのときのカメラの切り欠き（ディスプレイカットアウト）にも重ならないように
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            findPreference<androidx.preference.Preference>("version")?.summary =
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            // テーマはすぐに切り替える
            findPreference<androidx.preference.ListPreference>("theme")?.setOnPreferenceChangeListener { _, v ->
                AppSettings.applyTheme(ctx, v as String)
                true
            }
            findPreference<androidx.preference.Preference>("licenses")?.setOnPreferenceClickListener {
                showLicenses()
                true
            }
        }

        /** 使っているオープンソースのソフトウェアと、そのライセンスの一覧 */
        private fun showLicenses() {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("オープンソースライセンス")
                .setMessage(HtmlCompat.fromHtml(LICENSES_HTML, HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton("閉じる", null)
                .show()
            // リンクを押せるようにする（ブラウザが無い端末でも落ちないように）
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = SafeLinkMovementMethod
        }
    }

    /** リンクを開けるアプリが無い（Android TV など）ときに、落ちずに知らせる */
    private object SafeLinkMovementMethod : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean = try {
            super.onTouchEvent(widget, buffer, event)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(widget.context, "リンクを開けるアプリがありません", Toast.LENGTH_SHORT).show()
            true
        }

        override fun handleMovementKey(widget: TextView, buffer: Spannable, keyCode: Int, movementMetaState: Int, event: KeyEvent): Boolean = try {
            super.handleMovementKey(widget, buffer, keyCode, movementMetaState, event)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(widget.context, "リンクを開けるアプリがありません", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private companion object {
        val LICENSES_HTML = """
            <b>libVLC</b>（VideoLAN）<br>
            GNU Lesser General Public License v2.1 以降（LGPL-2.1+）<br>
            <a href="https://www.videolan.org/vlc/libvlc.html">https://www.videolan.org/vlc/libvlc.html</a><br>
            ソースコード：<a href="https://code.videolan.org/videolan/vlc-android">https://code.videolan.org/videolan/vlc-android</a><br>
            <br>
            <b>AndroidX</b>（Google）<br>
            Apache License 2.0<br>
            <a href="https://developer.android.com/jetpack/androidx">https://developer.android.com/jetpack/androidx</a><br>
            <br>
            <b>Material Components for Android</b>（Google）<br>
            Apache License 2.0<br>
            <a href="https://github.com/material-components/material-components-android">https://github.com/material-components/material-components-android</a><br>
            <br>
            <b>Kotlin・kotlinx.coroutines</b>（JetBrains）<br>
            Apache License 2.0<br>
            <a href="https://github.com/Kotlin/kotlinx.coroutines">https://github.com/Kotlin/kotlinx.coroutines</a><br>
            <br>
            <b>Coil</b>（Coil Contributors）・<b>Okio</b>（Square）<br>
            Apache License 2.0<br>
            <a href="https://github.com/coil-kt/coil">https://github.com/coil-kt/coil</a><br>
            <br>
            <b>ライセンスの全文</b><br>
            <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a><br>
            <a href="https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html">GNU LGPL v2.1</a>
        """.trimIndent()
    }
}
