package com.ryose.videoplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/** メニューの 1 項目（アイコン・名前・今の状態など） */
data class SheetItem(
    val icon: Int,
    val label: String,
    val detail: String? = null,
    /** オンになっている項目（お気に入り・シャッフルなど）はアイコンを強調する */
    val active: Boolean = false,
    val action: () -> Unit,
)

/** 画面の下から出てくる、アイコン付きのメニュー（大きめの行で押しやすく） */
object ActionSheet {

    fun show(context: Context, title: String?, items: List<SheetItem>, subtitle: String? = null) {
        if (items.isEmpty()) return
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(4), 0, px(16))
        }
        if (title != null) {
            column.addView(TextView(context).apply {
                text = title
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                paint.isFakeBoldText = true
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(px(24), px(8), px(24), if (subtitle == null) px(8) else 0)
            })
            if (subtitle != null) {
                column.addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(px(24), px(2), px(24), px(8))
                })
            }
        }
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        items.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(56)
                setPadding(px(24), px(6), px(24), px(6))
                setBackgroundResource(ripple)
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    item.action()
                }
            }
            row.addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(if (item.active) accent else muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(px(24), px(24)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), 0, 0, 0)
            }
            texts.addView(TextView(context).apply {
                text = item.label
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!item.detail.isNullOrEmpty()) {
                texts.addView(TextView(context).apply {
                    text = item.detail
                    setTextColor(if (item.active) accent else muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            column.addView(row)
        }
        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        // 横向きでも全部見えるように、最初から全体を開く
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
