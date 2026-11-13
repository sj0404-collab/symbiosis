// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import android.content.Context

/** Read in the launcher; pass the value to the isolated player in its intent. */
object KenjiLogSettings {
    private const val PREFS = "kenji_log_settings"
    private const val VERBOSE = "verbose"
    fun verbose(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(VERBOSE, false)
    fun setVerbose(context: Context, enabled: Boolean): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(VERBOSE, enabled).commit() }.getOrDefault(false)
}
