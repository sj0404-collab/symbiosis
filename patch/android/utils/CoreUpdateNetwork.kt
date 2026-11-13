// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
package org.yuzu.yuzu_emu.utils

import java.net.HttpURLConnection
import java.net.URL

/** Small HTTPS-only client. No arbitrary origins, credentials or redirects. */
object CoreUpdateNetwork {
    private val pagesHosts = setOf("sj0404-collab.github.io")
    private val assetHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    const val MANIFEST_URL = "https://sj0404-collab.github.io/symbiosis/core-test/current.txt"

    fun allowed(url: URL, manifest: Boolean): Boolean {
        val hosts = if (manifest) pagesHosts else assetHosts
        return url.protocol == "https" && url.host.lowercase() in hosts &&
            url.userInfo == null && url.ref == null && (url.port == -1 || url.port == 443)
    }

    private fun connect(start: String, manifest: Boolean): HttpURLConnection {
        var url = URL(start)
        repeat(6) {
            if (!allowed(url, manifest)) throw IllegalArgumentException("недопустимый адрес сервера")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", if (manifest) "text/plain" else "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IllegalStateException("пустое перенаправление")
                    url = URL(url, location)
                    conn.disconnect()
                } else if (code != 200) {
                    throw IllegalStateException("сервер ответил $code")
                } else return conn
            } catch (e: Exception) { conn.disconnect(); throw e }
        }
        throw IllegalStateException("слишком много перенаправлений")
    }

    fun manifest(): ByteArray {
        val conn = connect(MANIFEST_URL, true)
        try {
            if (conn.contentLengthLong > 2048) throw IllegalStateException("описание обновления слишком большое")
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (out.size() + count > 2048) throw IllegalStateException("описание обновления слишком большое")
                    out.write(buffer, 0, count)
                }
                return out.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    fun <T> withAsset(core: SignedCoreManifest.Core, consume: (java.io.InputStream) -> T): T {
        val conn = connect(core.url, false)
        try {
            val size = conn.contentLengthLong
            if (size >= 0 && size != core.bytes) throw IllegalStateException("размер ответа сервера не совпадает")
            return conn.inputStream.use(consume)
        } finally { conn.disconnect() }
    }
}
