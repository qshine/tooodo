package com.tooodo.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** targetSdk 35 强制全面屏，把状态栏、导航栏和键盘的高度加到根布局的 padding 上。 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
