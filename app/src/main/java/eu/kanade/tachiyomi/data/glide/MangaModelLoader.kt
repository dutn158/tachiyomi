package eu.kanade.tachiyomi.data.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.*
import com.bumptech.glide.load.model.stream.StreamModelLoader
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.source.SourceManager
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * A class for loading a cover associated with a [Manga] that can be present in our own cache.
 * Coupled with [MangaDataFetcher], this class allows to implement the following flow:
 *
 * - Check in RAM LRU.
 * - Check in disk LRU.
 * - Check in this module.
 * - Fetch from the network connection.
 *
 * @param context the application context.
 */
class MangaModelLoader(context: Context) : StreamModelLoader<Manga> {

    /**
     * Cover cache where persistent covers are stored.
     */
    @Inject lateinit var coverCache: CoverCache

    /**
     * Source manager.
     */
    @Inject lateinit var sourceManager: SourceManager

    /**
     * Base network loader.
     */
    private val baseLoader = Glide.buildModelLoader(GlideUrl::class.java,
            InputStream::class.java, context)

    /**
     * LRU cache whose key is the thumbnail url of the manga, and the value contains the request url
     * and the file where it should be stored in case the manga is a favorite.
     */
    private val modelCache = ModelCache<String, Pair<GlideUrl, File>>(100)

    /**
     * Map where request headers are stored for a source.
     */
    private val cachedHeaders = hashMapOf<Int, LazyHeaders>()

    init {
        App.get(context).component.inject(this)
    }

    /**
     * Factory class for creating [MangaModelLoader] instances.
     */
    class Factory : ModelLoaderFactory<Manga, InputStream> {

        override fun build(context: Context, factories: GenericLoaderFactory)
                = MangaModelLoader(context)

        override fun teardown() {}
    }

    /**
     * Returns a [MangaDataFetcher] for the given manga or null if the url is empty.
     *
     * @param manga the model.
     * @param width the width of the view where the resource will be loaded.
     * @param height the height of the view where the resource will be loaded.
     */
    override fun getResourceFetcher(manga: Manga,
                                    width: Int,
                                    height: Int): DataFetcher<InputStream>? {
        // Check thumbnail is not null or empty
        val url = manga.thumbnail_url
        if (url.isNullOrEmpty()) {
            return null
        }

        // Obtain the request url and the file for this url from the LRU cache, or calculate it
        // and add them to the cache.
        val (glideUrl, file) = modelCache.get(url, width, height) ?:
            Pair(GlideUrl(url, getHeaders(manga)), coverCache.getCoverFile(url)).apply {
                modelCache.put(url, width, height, this)
            }

        // Get the network fetcher for this request url.
        val networkFetcher = baseLoader.getResourceFetcher(glideUrl, width, height)

        // Return an instance of our fetcher providing the needed elements.
        return MangaDataFetcher(networkFetcher, file, manga)
    }

    /**
     * Returns the request headers for a source copying its OkHttp headers and caching them.
     *
     * @param manga the model.
     */
    fun getHeaders(manga: Manga): LazyHeaders {
        return cachedHeaders.getOrPut(manga.source) {
            val source = sourceManager.get(manga.source)!!

            LazyHeaders.Builder().apply {
                for ((key, value) in source.requestHeaders.toMultimap()) {
                    addHeader(key, value[0])
                }
            }.build()
        }
    }

}
