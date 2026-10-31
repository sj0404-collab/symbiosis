// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.LifecycleOwner
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game

class GameIconFetcher(
    private val game: Game,
    private val options: Options
) : Fetcher {
    /**
     * ПОЧЕМУ ЗДЕСЬ ПАДАЛО
     *
     * Строка была такая:
     *
     *     drawable = decodeGameIcon(game.path)!!.toDrawable(...)
     *
     * `!!` на результате, который штатно бывает null. Пустая или битая
     * обложка - это `BitmapFactory.decodeByteArray` возвращает null, и
     * тогда `!!` кидает NullPointerException. А кидает он его внутри
     * корутины загрузчика Coil, куда `.error(R.drawable.default_icon)`
     * не дотягивается: заглушка показывается, когда fetch вернул отказ,
     * а не когда он бросил исключение. Исключение уходит наверх и роняет
     * приложение.
     *
     * Пустая обложка - не редкость, а норма: её нет у homebrew, у части
     * NSP, у любого файла, чей заголовок не расшифровался текущими
     * ключами. Именно поэтому падало "при сканировании, когда ищет
     * обложки" - список доходил до первого такого файла.
     *
     * Теперь ни одного `!!` на всём пути, а любой отказ - это заглушка.
     */
    override suspend fun fetch(): FetchResult {
        // Обложки нет — это отказ, а не «серая картинка вместо обложки».
        //
        // Раньше здесь возвращался DrawableResult с default_icon, то есть
        // УСПЕШНЫЙ результат. Coil клал его в кэш по пути файла, а экран
        // считал загрузку удачной и показывал картинку вместо монограммы.
        // Итог: у файлов без иконки квадрат с заглушкой, а после смены
        // ключей — та же заглушка из кэша, сколько ни пересканируй.
        if (GameIconUtils.isKnownWithoutIcon(game.path)) {
            throw IllegalStateException("у этого файла нет обложки")
        }
        val bitmap = decodeGameIcon(game.path)
            ?: run {
                GameIconUtils.markWithoutIcon(game.path)
                throw IllegalStateException("обложка не читается: нет ключей или файл битый")
            }
        return DrawableResult(
            drawable = bitmap.toDrawable(options.context.resources),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    private fun decodeGameIcon(uri: String): Bitmap? = runCatching {
        // getIcon читает и расшифровывает ROM. На битом файле нативный
        // слой может бросить, и раньше это тоже улетало наверх.
        val data = GameMetadata.getIcon(uri)
        if (data == null || data.isEmpty()) return@runCatching null

        // Сначала только размеры, без выделения памяти под пиксели.
        //
        // Обложка Switch - 256x256, но в файле лежит что угодно, и
        // повреждённый заголовок объявляет какие угодно размеры.
        // decodeByteArray верил им на слово и пытался выделить буфер под
        // объявленное: на 3 ГБ памяти это OutOfMemoryError, то есть
        // второй способ уронить приложение на обложках.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }.getOrNull()

    /** Уменьшает картинку до разумного размера степенями двойки. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_ICON_EDGE || height / sample > MAX_ICON_EDGE) {
            sample *= 2
        }
        return sample
    }

    class Factory : Fetcher.Factory<Game> {
        override fun create(data: Game, options: Options, imageLoader: ImageLoader): Fetcher =
            GameIconFetcher(data, options)
    }

    private companion object {
        /**
         * Больше этого обложке быть незачем.
         *
         * У Switch она 256x256. Ячейка списка меньше, так что запас
         * четырёхкратный, а защита - от файла, который объявляет размер
         * в тысячи пикселей.
         */
        const val MAX_ICON_EDGE = 512
    }
}

class GameIconKeyer : Keyer<Game> {
    override fun key(data: Game, options: Options): String = data.path
}

object GameIconUtils {
    /**
     * Запросы, которые ещё выполняются, по ячейке.
     *
     * ПОЧЕМУ БЕЗ ЭТОГО ОБЛОЖКИ «СЪЕЗЖАЛИ»
     *
     *   Плитка переиспользуется: одна и та же ImageView показывает сначала
     *   одну игру, потом другую. Coil рисует картинку в ImageView сам, как
     *   только она готова. Запрос прошлой игры при этом никто не отменял, и
     *   он приходил позже нового — прямо поверх уже показанной обложки
     *   соседа. Проверка метки в колбэке от этого не спасает: картинка к
     *   тому моменту уже нарисована, колбэк лишь управляет видимостью.
     *
     *   Поэтому перед новым запросом старый отменяется. Слабая карта — чтобы
     *   не держать ссылки на ушедшие ячейки.
     */
    private val inflight = java.util.WeakHashMap<ImageView, coil.request.Disposable>()

    /**
     * Файлы, у которых обложки нет.
     *
     * Иначе каждый повторный показ плитки заново читал и расшифровывал ROM —
     * на списке из сотни homebrew это заметно. Записи сбрасываются в
     * [clearCache]: после смены ключей обложка может появиться.
     */
    private val withoutIcon = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    internal fun isKnownWithoutIcon(path: String): Boolean = path in withoutIcon
    internal fun markWithoutIcon(path: String) { withoutIcon.add(path) }

    /** Прекратить загрузку в эту ячейку и убрать картинку. */
    fun cancel(imageView: ImageView) {
        inflight.remove(imageView)?.dispose()
        imageView.setImageDrawable(null)
    }

    private val imageLoader = ImageLoader.Builder(YuzuApplication.appContext)
        .components {
            add(GameIconKeyer())
            add(GameIconFetcher.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(YuzuApplication.appContext)
                .maxSizePercent(0.25)
                .build()
        }
        .build()

    /**
     * Забыть все обложки.
     *
     * ПОЧЕМУ ЭТО НУЖНО
     *   Coil кэширует картинку по ключу game.path и держит её в памяти.
     *   Иконка читается из файла ОДИН раз - в момент первого сканирования,
     *   и если тогда ключи были старые или неполные, ReadIcon() вернул
     *   пусто, а Coil запомнил заглушку под этим путём.
     *
     *   Дальше можно сколько угодно менять prod.keys и title.keys: игра
     *   начнёт запускаться, потому что её читают заново, а обложка так и
     *   останется серым квадратом - её берут из памяти, файл больше не
     *   трогают.
     *
     *   Нативный кэш метаданных при пересканировании чистится
     *   (GameMetadata.resetMetadata в GameHelper), а этот - нет. Теперь
     *   чистятся оба.
     */
    fun clearCache() {
        withoutIcon.clear()
        runCatching { imageLoader.memoryCache?.clear() }
            .onFailure { android.util.Log.e("Symbiosis", "не удалось очистить кэш иконок", it) }
    }

    fun loadGameIcon(
        game: Game,
        imageView: ImageView,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        // Ошибка не должна подменять обложку серой заглушкой поверх
        // монограммы: у большинства файлов иконки нет вовсе, и «есть буква»
        // читается лучше, чем «есть серая картинка». Поэтому при отказе
        // картинка просто прячется, а монограмма остаётся.
        // Прошлый запрос на эту же ячейку отменяется: иначе его ответ
        // придёт позже нового и нарисует чужую обложку (см. inflight).
        inflight.remove(imageView)?.dispose()
        val request = ImageRequest.Builder(YuzuApplication.appContext)
            .data(game)
            .target(imageView)
            .listener(
                onSuccess = { _, _ -> onResult?.invoke(true) },
                onError = { _, _ -> onResult?.invoke(false) }
            )
            .build()
        inflight[imageView] = imageLoader.enqueue(request)
    }

    /**
     * Есть ли у этой игры обложка — без загрузки в ImageView.
     *
     * Нужно там, где решается вид строки (показывать картинку или букву), а
     * не рисуется сама картинка.
     */
    suspend fun hasCover(game: Game): Boolean = runCatching {
        val drawable = imageLoader.execute(
            ImageRequest.Builder(YuzuApplication.appContext)
                .data(game)
                .build()
        ).drawable
        drawable != null
    }.getOrDefault(false)

    /**
     * Обложка как Bitmap. Второе место с тем же `!!`.
     *
     * `execute()` возвращает результат с пустым drawable, если запрос не
     * удался - а `!!` превращал это в NullPointerException. Отсюда её
     * берут ярлыки на рабочий стол и экран загрузки игры.
     */
    suspend fun getGameIcon(lifecycleOwner: LifecycleOwner, game: Game): Bitmap {
        val request = ImageRequest.Builder(YuzuApplication.appContext)
            .data(game)
            .lifecycle(lifecycleOwner)
            .error(R.drawable.default_icon)
            .build()
        val drawable = runCatching { imageLoader.execute(request).drawable }.getOrNull()
            ?: ResourcesCompat.getDrawable(
                YuzuApplication.appContext.resources,
                R.drawable.default_icon,
                null
            )
        return drawable?.toBitmap(config = Bitmap.Config.ARGB_8888)
        // Совсем последний рубеж: даже стандартная иконка не прочиталась.
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    suspend fun getShortcutIcon(lifecycleOwner: LifecycleOwner, game: Game): IconCompat {
        val layerDrawable = ResourcesCompat.getDrawable(
            YuzuApplication.appContext.resources,
            R.drawable.shortcut,
            null
        ) as LayerDrawable
        layerDrawable.setDrawableByLayerId(
            R.id.shortcut_foreground,
            getGameIcon(lifecycleOwner, game).toDrawable(YuzuApplication.appContext.resources)
        )
        val inset = YuzuApplication.appContext.resources
            .getDimensionPixelSize(R.dimen.icon_inset)
        layerDrawable.setLayerInset(1, inset, inset, inset, inset)
        return IconCompat.createWithAdaptiveBitmap(
            layerDrawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        )
    }
}
