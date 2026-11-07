// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.yuzu.yuzu_emu.utils.KenjiUiRequest

/** Android rendering of copied Kenji dialog data; no native pointer is kept. */
class KenjiUiPresenter(
    private val activity: Activity,
    private val answer: (Long, Boolean, String) -> Boolean,
) {
    private var dialog: AlertDialog? = null
    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value + .5f).toInt()

    /** Must be called on the main thread by KenjiUiCoordinator. */
    fun show(id: Long, request: KenjiUiRequest) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activity.isFinishing || activity.isDestroyed) {
            answer(id, false, "")
            return
        }
        dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(Color.rgb(31, 33, 40))
        }
        fun addText(value: String) {
            if (value.isNotEmpty()) content.addView(TextView(activity).apply {
                text = value
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        addText(request.message)
        val input = if (request.type == 2) EditText(activity).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            hint = request.watermark
            inputType = if (request.mode == 1) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else InputType.TYPE_CLASS_TEXT
            setText(request.initialText)
            setSelection(length())
            content.addView(this)
        } else null
        addText(request.subtitle)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.rgb(31, 33, 40))
            isFillViewport = true
            addView(content)
        }
        val window = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(request.title)
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("ОК", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog = window
        window.setOnShowListener {
            window.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (answer(id, true, input?.text?.toString().orEmpty())) {
                        window.dismiss()
                    } else {
                        input?.error = "Проверьте длину и символы текста."
                    }
                }
            }
            window.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (answer(id, false, "")) window.dismiss()
                }
            }
            if (input != null) {
                input.requestFocus()
                window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        window.setOnDismissListener { if (dialog === window) dialog = null }
        window.show()
    }

    /** Dismissal itself does not send a duplicate reply. */
    fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper())
        dialog?.dismiss()
        dialog = null
    }
}
