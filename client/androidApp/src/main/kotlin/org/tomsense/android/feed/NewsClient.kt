package org.tomsense.android.feed

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
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

    /**
     * `kind`, not `action`.
     *
     * The Worker requires {id, kind} and 400s on anything else. This field was
     * named `action`, so every thumbs up and down from this app was silently
     * rejected — the card vanished locally, which looked exactly like success,
     * while the taste vector never moved and the article was never dismissed.
     * It returned on the next refresh and read as a flaky button.
     */
    @Serializable
    private data class Feedback(val id: String, val kind: String)

    @Serializable
    private data class UndoRequest(val id: String)

    /**
     * The status check is not ceremony.
     *
     * Ktor does not throw on a non-2xx by default, so without it a 401 from a
     * rotated key gets handed to the JSON parser and surfaces as an
     * incomprehensible deserialization error about the Worker's error body —
     * which looks nothing like "your key is wrong".
     */
    suspend fun feed(config: Config, limit: Int = 30): Feed {
        val response = http.get("${config.baseUrl}/feed.json?limit=$limit") {
            header("Authorization", "Bearer ${config.apiKey}")
        }
        if (!response.status.isSuccess()) {
            throw IOException("news-worker returned HTTP ${response.status.value}")
        }
        return response.body()
    }

    /** One declared interest ("Philadelphia Eagles NFL football"). */
    @Serializable
    data class Interest(
        val id: Long = 0,
        val name: String = "",
        @SerialName("query_text") val queryText: String = "",
        val enabled: Int = 1,
        val weight: Double = 1.0,
    )

    @Serializable
    private data class Interests(val interests: List<Interest> = emptyList())

    /**
     * The topics this user told the news feed they follow. Read by the panel
     * to decide which teams' games to show — so what appears is driven by
     * the user's own settings, never by a team baked into the app.
     */
    suspend fun interests(config: Config): List<Interest> {
        val response = http.get("${config.baseUrl}/interests") {
            header("Authorization", "Bearer ${config.apiKey}")
        }
        if (!response.status.isSuccess()) {
            throw IOException("news-worker returned HTTP ${response.status.value}")
        }
        return response.body<Interests>().interests.filter { it.enabled != 0 }
    }

    /** Every interest, disabled ones included — for the editor in Settings. */
    suspend fun allInterests(config: Config): List<Interest> {
        val response = http.get("${config.baseUrl}/interests") {
            header("Authorization", "Bearer ${config.apiKey}")
        }
        if (!response.status.isSuccess()) throw IOException("news-worker returned HTTP ${response.status.value}")
        return response.body<Interests>().interests
    }

    @Serializable
    private data class InterestWrite(
        val id: Long? = null,
        val name: String,
        val queryText: String,
        val enabled: Boolean = true,
        // Sent every time: the worker resets an omitted weight to 1.0, so a
        // toggle would otherwise silently undo a tuned weight.
        val weight: Double = 1.0,
    )

    /**
     * Create (id null) or update an interest. The worker re-embeds the text on
     * every write, so this is also how a description edit takes effect.
     */
    suspend fun saveInterest(
        config: Config,
        id: Long?,
        name: String,
        queryText: String,
        enabled: Boolean = true,
        weight: Double = 1.0,
    ) {
        val res = http.post("${config.baseUrl}/interests") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(InterestWrite(id, name, queryText, enabled, weight))
        }
        if (!res.status.isSuccess()) throw IOException("interest rejected: HTTP ${res.status.value}")
    }

    suspend fun deleteInterest(config: Config, id: Long) {
        val res = http.delete("${config.baseUrl}/interests?id=$id") {
            header("Authorization", "Bearer ${config.apiKey}")
        }
        if (!res.status.isSuccess()) throw IOException("delete failed: HTTP ${res.status.value}")
    }

    /**
     * Thumbs up or down.
     *
     * "less" must also dismiss the article, not merely nudge the taste vector
     * — otherwise the next refresh serves it straight back and the button
     * looks broken. The Worker handles that; this only has to send it.
     */
    /**
     * Take back the last rating on an article.
     *
     * A real undo, not a compensating opposite vote. Voting "more" to cancel a
     * "less" leaves two events in the append-only log and a taste vector only
     * NEAR where it started — and the log is what a rebuild re-derives from,
     * so the mistake would return the next time weights are retuned. The
     * Worker drops the event, un-dismisses the article and re-derives.
     */
    suspend fun undo(config: Config, id: String) {
        val res = http.post("${config.baseUrl}/feedback/undo") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(UndoRequest(id))
        }
        if (!res.status.isSuccess()) {
            throw IOException("undo rejected: HTTP ${res.status.value}")
        }
    }

    suspend fun feedback(config: Config, id: String, kind: String) {
        val res = http.post("${config.baseUrl}/feedback") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(Feedback(id, kind))
        }
        // Checked, not fired and forgotten. This call silently 400'd for its
        // whole life because nothing ever looked at the response.
        if (!res.status.isSuccess()) {
            throw IOException("feedback '$kind' rejected: HTTP ${res.status.value}")
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
