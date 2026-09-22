package org.tomsense.android.feed

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tomsense.android.Launch
import org.tomsense.android.TomsenseApp
import org.tomsense.ui.decodeImageBytes

/**
 * What lives left of the home screen.
 *
 * Four things, in the order they earn their place on a glance rather than a
 * browse: ask, at-a-glance, recent chats, news. The Google app puts search at
 * the top for the same reason — most openings of this panel are a question,
 * not a reading session.
 *
 * Content loads on OPEN, not on attach. The launcher binds this service at
 * boot and resumes it constantly; fetching there would hammer the worker for
 * a panel nobody looked at.
 */
@Composable
fun FeedPanel(app: TomsenseApp, state: FeedPanelState) {
    LaunchedEffect(state.visible) {
        if (state.visible) state.load(app)
    }

    // NO view-level alpha here. Fading is done with the WINDOW alpha in
    // FeedOverlayService — a Modifier.alpha driven by scroll progress renders
    // a fully transparent panel whenever the launcher does not deliver a
    // scroll event, which looks exactly like a blank home screen.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        AskBox(state)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.glance.isNotEmpty()) {
                item { GlanceStrip(state) }
            }

            if (state.recent.isNotEmpty()) {
                item { SectionLabel("Recent") }
                items(state.recent, key = { "c-" + it.first }) { (id, title) ->
                    RecentRow(title) { state.openChat(id) }
                }
            }

            item { SectionLabel("News") }

            if (state.loading && state.news.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Column {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { state.openSettings() }) { Text("Set up the news feed") }
                    }
                }
            }

            items(state.news, key = { it.id }) { item ->
                NewsCard(
                    item = item,
                    thumb = state.thumbs[item.id],
                    onOpen = { state.openArticle(item) },
                    onMore = { state.rate(item, "more") },
                    onLess = { state.rate(item, "less") },
                )
            }
        }
    }
}

@Composable
private fun AskBox(state: FeedPanelState) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { state.draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask TomSense") },
            singleLine = true,
        )
        IconButton(onClick = { state.ask() }, enabled = state.draft.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
        }
    }
}

/**
 * The line the Google app is actually missed for.
 *
 * Built from device tools that already exist, so it costs no network and no
 * model call — the panel should be readable before anything has loaded.
 */
@Composable
private fun GlanceStrip(state: FeedPanelState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.glance.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RecentRow(title: String, onClick: () -> Unit) {
    Text(
        title.ifBlank { "New chat" },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun NewsCard(
    item: NewsClient.Item,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onLess: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            thumb?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.outlet.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Vector icons, never emoji: emoji render in the system
                    // colour font and ignore tinting, so they look pasted on
                    // against Material You.
                    IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.ThumbUp,
                            contentDescription = "More like this",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = onLess, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.ThumbDown,
                            contentDescription = "Less like this",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Panel state and the actions it can take. Held by the service, not Compose. */
class FeedPanelState(
    private val onOpenApp: (Intent) -> Unit,
    private val onDismiss: () -> Unit,
) {
    var visible by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var draft by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var news by mutableStateOf<List<NewsClient.Item>>(emptyList())
    var thumbs by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
    var glance by mutableStateOf<List<String>>(emptyList())
    var recent by mutableStateOf<List<Pair<String, String>>>(emptyList())

    private var app: TomsenseApp? = null
    private var lastLoad = 0L

    suspend fun load(application: TomsenseApp) {
        app = application

        // Local first, always: recent chats and the glance line come from
        // SQLite and device state, so the panel has content before any network
        // call resolves — or if none ever does.
        recent = withContext(Dispatchers.Default) {
            application.db.schemaQueries.conversationList().executeAsList()
                .take(3)
                .map { it.id to it.title }
        }
        glance = withContext(Dispatchers.Default) { buildGlance(application) }

        // The worker's own snapshot lasts ten minutes; refetching faster than
        // that returns the same order and only costs an impression.
        val now = System.currentTimeMillis()
        if (news.isNotEmpty() && now - lastLoad < 5 * 60_000) return
        lastLoad = now

        loading = true
        error = null
        val config = NewsClient.config(application)
        if (!config.isComplete) {
            loading = false
            error = "No news source configured."
            return
        }

        val client = NewsClient(application.httpClient)
        val result = runCatching { client.feed(config) }.getOrNull()
        loading = false

        if (result == null) {
            error = if (news.isEmpty()) "Couldn't reach the news feed." else null
            return
        }
        news = result.items

        // Images after the text is already on screen: a card with a title is
        // useful, a card waiting for a thumbnail is not.
        loadThumbs(application, client, config, result.items)
    }

    private suspend fun loadThumbs(
        application: TomsenseApp,
        client: NewsClient,
        config: NewsClient.Config,
        items: List<NewsClient.Item>,
    ) = withContext(Dispatchers.IO) {
        val out = thumbs.toMutableMap()
        // Capped: the panel shows a handful before any scrolling, and every
        // fetch is an edge transformation that counts against quota.
        for (item in items.take(12)) {
            val url = item.imageUrl ?: continue
            if (out.containsKey(item.id)) continue
            val bytes = runCatching {
                application.httpClient.get(client.thumbUrl(config, url, 480)) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }.body<ByteArray>()
            }.getOrNull() ?: continue
            decodeImageBytes(bytes)?.let { out[item.id] = it }
        }
        thumbs = out
    }

    /**
     * One or two lines of useful now.
     *
     * Read straight from the device tools rather than asking a model: this has
     * to be right and instant, and "what is my next meeting" is a lookup, not
     * a question.
     */
    private suspend fun buildGlance(application: TomsenseApp): List<String> = runCatching {
        val out = mutableListOf<String>()
        val calendar = application.tools.call(
            "get_calendar",
            kotlinx.serialization.json.buildJsonObject {
                put("days", kotlinx.serialization.json.JsonPrimitive(1))
            },
        )
        // Best-effort: the tool returns JSON, and a missing permission returns
        // an error object. Either way a blank strip beats a broken one.
        if (!calendar.contains("error", ignoreCase = true) && calendar.length > 8) {
            out += summariseCalendar(calendar)
        }
        out.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun summariseCalendar(json: String): String = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val events = root.jsonObjectOrNull()?.get("events")?.jsonArrayOrNull() ?: return@runCatching ""
        val first = events.firstOrNull()?.jsonObjectOrNull() ?: return@runCatching "Nothing on the calendar today."
        val title = first["title"]?.primitiveContent().orEmpty()
        val start = first["start"]?.primitiveContent().orEmpty()
        if (title.isBlank()) "" else "Next: $title${if (start.isNotBlank()) " · $start" else ""}"
    }.getOrDefault("")

    fun ask() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        app?.let { onOpenApp(Launch.intent(it, prefill = text)) }
    }

    fun openChat(@Suppress("UNUSED_PARAMETER") id: String) {
        app?.let { onOpenApp(Launch.intent(it)) }
    }

    fun openSettings() {
        app?.let {
            onOpenApp(Intent(it, org.tomsense.android.SettingsActivity::class.java))
        }
    }

    fun openArticle(item: NewsClient.Item) {
        // The article URL directly, not the worker's /click redirect: a
        // browser cannot send an Authorization header, so the authenticated
        // redirect would land on a 401. Feedback is posted separately.
        onOpenApp(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
        app?.let { application ->
            CoroutineScope(Dispatchers.IO).launch {
                val config = NewsClient.config(application)
                if (config.isComplete) {
                    runCatching { NewsClient(application.httpClient).feedback(config, item.id, "click") }
                }
            }
        }
    }

    fun rate(item: NewsClient.Item, action: String) {
        // Removed from view immediately on "less": leaving it there while the
        // request flies makes the button look broken.
        if (action == "less") news = news.filterNot { it.id == item.id }
        app?.let { application ->
            CoroutineScope(Dispatchers.IO).launch {
                val config = NewsClient.config(application)
                if (config.isComplete) {
                    runCatching { NewsClient(application.httpClient).feedback(config, item.id, action) }
                }
            }
        }
    }

    fun dismiss() = onDismiss()
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
    this as? kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
    this as? kotlinx.serialization.json.JsonArray

private fun kotlinx.serialization.json.JsonElement.primitiveContent() =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content
