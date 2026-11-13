// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
import org.yuzu.yuzu_emu.utils.CoreUpdateNetwork
import java.net.URL
fun main() {
    fun allow(s: String, m: Boolean) = CoreUpdateNetwork.allowed(URL(s), m)
    check(allow(CoreUpdateNetwork.MANIFEST_URL, true))
    check(!allow("http://sj0404-collab.github.io/symbiosis/core-test/current.txt", true))
    check(!allow("https://sj0404-collab.github.io.evil.test/", true))
    check(!allow("https://user@sj0404-collab.github.io/", true))
    check(!allow("https://sj0404-collab.github.io:8443/", true))
    check(!allow("https://sj0404-collab.github.io/#frag", true))
    check(allow("https://github.com/sj0404-collab/symbiosis/releases/download/engine-test-1/core.so", false))
    check(allow("https://release-assets.githubusercontent.com/a?token=abc", false))
    check(!allow("https://github.com.evil.test/", false))
    check(!allow("http://release-assets.githubusercontent.com/", false))
    check(!allow("https://localhost/", false))
    println("CoreUpdateNetwork: HTTPS origin, redirect host and credential restrictions passed")
}
