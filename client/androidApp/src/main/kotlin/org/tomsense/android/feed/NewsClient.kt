package org.tomsense.android.feed

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val Context.newsStore by preferencesDataStore("news")

/**
 * The news-worker client.
 *
 * A deliberately thin port of news-widget's NewsRepository. The ranking,
 * balance lanes and dedupe all live in the Worker and none of it moves — this
 * only fetches an already-ordered list and sends feedback back.
 *
 * Authentication is `Authorization: Bearer`, never `?key=`. The Worker stopped
 * accepting the query form on purpose: it leaks through history, referrers and
 * shoulder-surfing, and a key that has been in a URL is a key that has to be
 * rotated.
 */
class NewsClient(private val http: HttpClient) {

    @Serializable
    data class Item(
        val id: String,
        val title: String,
        val url: String,
        val clickUrl: String = "",
        val outlet: String? = null,
        val imageUrl: String? = null,
        val publishedAt: Long = 0,
        val topic: String? = null,
        val lane: String? = null,
        @SerialName("outletBias") val outletBias: Float? = null,
    )

    @Serializable
    data class Feed(
        val generatedAt: Long = 0,
        val coldStart: Boolean = false,
        val items: List<Item> = emptyList(),
    )

    @Serializable
    private data class Feedback(val id: String, val action: String)

    suspend fun feed(config: Config, limit: Int = 30): Feed =
        http.get("${config.baseUrl}/feed.json?limit=$limit") {
            header("Authorization", "Bearer ${config.apiKey}")
        }.body()

    /**
     * Thumbs up or down.
     *
     * "less" must also dismiss the article, not merely nudge the taste vector
     * — otherwise the next refresh serves it straight back and the button
     * looks broken. The Worker handles that; this only has to send it.
     */
    suspend fun feedback(config: Config, id: String, action: String) {
        http.post("${config.baseUrl}/feedback") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(Feedback(id, action))
        }
    }

    /**
     * A thumbnail URL sized at the edge.
     *
     * Never the publisher's original: og:image is editorial size — measured at
     * 14.4 MB across 30 articles, single images up to 3.8 MB — all destined
     * for a slot a few hundred pixels wide. The Worker's /img proxy resizes at
     * Cloudflare's edge and takes a feed refresh from 14.4 MB to under 400 KB.
     */
    fun thumbUrl(config: Config, imageUrl: String, width: Int): String =
        "${config.baseUrl}/img?w=$width&u=" + java.net.URLEncoder.encode(imageUrl, "UTF-8")

    data class Config(val baseUrl: String, val apiKey: String) {
        val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("base_url")
        private val API_KEY = stringPreferencesKey("api_key")

        suspend fun config(context: Context): Config {
            val prefs = context.newsStore.data.first()
            return Config(
                baseUrl = prefs[BASE_URL]?.trimEnd('/') ?: "",
                apiKey = prefs[API_KEY] ?: "",
            )
        }

        suspend fun save(context: Context, baseUrl: String, apiKey: String) {
            context.newsStore.edit {
                it[BASE_URL] = baseUrl.trim().trimEnd('/')
                it[API_KEY] = apiKey.trim()
            }
        }
    }
}
