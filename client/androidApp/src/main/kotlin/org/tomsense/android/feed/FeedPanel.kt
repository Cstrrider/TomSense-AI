package org.tomsense.android.feed

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tomsense.android.Launch
import org.tomsense.android.TomsenseApp
import org.tomsense.android.TurnRunner
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.WireMessage
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
    // Swipe-to-dismiss is NOT here. It is intercepted above Compose in
    // DismissFrameLayout, because the LazyColumn below claims horizontal
    // drags before a parent pointerInput ever sees them.
    // Surface, NOT a Column with a background modifier. A background is only
    // paint: it leaves LocalContentColor at its default of BLACK, so any Text
    // outside a Card that does not set a colour explicitly renders black on a
    // dark panel and looks like a missing font. Surface sets the matching
    // content colour, which is the actual fix rather than colouring each
    // offender one at a time.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Ask first, because most openings of this panel are a question
                // rather than a reading session — the same reason the Google app
                // puts search at the top.
                item { AskCard(state) }
    
                if (state.calendar.isNotEmpty()) {
                    item { CalendarCard(state) }
                }
    
                if (!state.insights.isEmpty) {
                    item { InsightsCard(state) }
                }
    
                // Recent chats live INSIDE the ask card now — they are the same
                // thing as asking, just already started.
    
                item {
                    SectionLabel(
                        "News",
                        onRefresh = { state.refresh() },
                        busy = state.loading,
                    )
                }
    
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
}

/**
 * Ask, and read the answer without leaving the home screen.
 *
 * The turn runs HERE rather than launching the app. Handing a question to an
 * activity meant every "what time is my meeting" cost a cold start and a
 * context switch, which is most of the reason to have a panel at all. The
 * reply streams into the card; the app is one tap away when the answer needs
 * following up.
 */
@Composable
private fun AskCard(state: FeedPanelState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { state.draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask TomSense") },
                    singleLine = true,
                )
                IconButton(
                    onClick = { state.ask() },
                    enabled = state.draft.isNotBlank() && !state.asking,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                }
            }

            if (state.asking && state.reply.isBlank()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            if (state.reply.isNotBlank()) {
                Text(state.reply, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { state.openAsked() }) { Text("Open in app") }
                    TextButton(onClick = { state.clearReply() }) { Text("Clear") }
                }
            }

            // Recent chats belong to this card: continuing a conversation and
            // starting one are the same action, and on the panel background
            // they were unreadable anyway.
            if (state.recent.isNotEmpty()) {
                HorizontalDivider(
                    Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.recent.forEach { (id, title) ->
                    RecentRow(title) { state.openChat(id) }
                }
            }
        }
    }
}

/**
 * Today, events and reminders together.
 *
 * Reminders set through the assistant land in the calendar provider, so they
 * arrive from the same read and belong in the same card — splitting them would
 * be an implementation detail leaking into the UI.
 */
@Composable
private fun CalendarCard(state: FeedPanelState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.calendar.forEach { entry ->
                Column {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOfNotNull(
                        if (entry.allDay) "All day" else entry.start.timeOnly(),
                        entry.location,
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The things worth knowing that are not news and not the calendar.
 *
 * Device state and feed balance cost nothing — they are already in memory.
 * Weather is one keyless request. The written summary is the only part that
 * costs a model call, so it is generated at most twice an hour and the card
 * renders perfectly well without it.
 */
@Composable
private fun InsightsCard(state: FeedPanelState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Insights",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.insights.summary.isNotBlank()) {
                Text(state.insights.summary, style = MaterialTheme.typography.bodyMedium)
            }
            state.insights.lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "2026-09-23T11:00" -> "11:00". Anything unexpected is passed through. */
private fun String.timeOnly(): String =
    substringAfter('T', missingDelimiterValue = this).take(5).ifBlank { this }

@Composable
private fun SectionLabel(text: String, onRefresh: (() -> Unit)? = null, busy: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        onRefresh?.let { action ->
            // The spinner replaces the button rather than sitting beside it,
            // so a second tap during a refresh is impossible by construction.
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = action, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh news",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(title: String, onClick: () -> Unit) {
    Text(
        title.ifBlank { "New chat" },
        style = MaterialTheme.typography.bodyMedium,
        // Explicit, not inherited. This is exactly the text that was rendering
        // black on the dark panel, and stating the colour means it cannot go
        // invisible again if it is ever moved outside a Card.
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/** Shared with the in-app News tab — the panel is not the only surface. */
@Composable
internal fun NewsCard(
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
    var calendar by mutableStateOf<List<CalendarEntry>>(emptyList())
    var insights by mutableStateOf(Insights())
    var recent by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** The answer to the question asked in the panel, streaming as it arrives. */
    var reply by mutableStateOf("")
    var asking by mutableStateOf(false)

    private var app: TomsenseApp? = null
    private var lastLoad = 0L
    private var lastSummary = 0L

    /**
     * The panel's own conversation, created on first use.
     *
     * One thread rather than one per question, so a follow-up asked from the
     * panel has the previous answer behind it — and so the app's chat list
     * does not fill up with one-line conversations.
     */
    private var panelConvId: String? = null
    private var runner: TurnRunner? = null
    private var turn: Job? = null

    /**
     * @param force skips the refetch cooldown. Only a deliberate pull-to-
     * refresh should set it — an automatic reload inside the window would just
     * spend an impression to receive the same snapshot back.
     */
    suspend fun load(application: TomsenseApp, force: Boolean = false) {
        app = application

        // Local first, always: recent chats and the glance line come from
        // SQLite and device state, so the panel has content before any network
        // call resolves — or if none ever does.
        recent = withContext(Dispatchers.Default) {
            application.db.schemaQueries.conversationList().executeAsList()
                .take(3)
                .map { it.id to it.title }
        }
        calendar = withContext(Dispatchers.Default) { readCalendar(application) }

        // Device state costs nothing and weather is one keyless request, so
        // both run before the feed; each degrades to a missing line rather
        // than failing the card.
        insights = insights.copy(
            device = withContext(Dispatchers.Default) { readDevice(application) },
            weather = withContext(Dispatchers.Default) { readWeather(application) },
        )

        // The worker's own snapshot lasts ten minutes; refetching faster than
        // that returns the same order and only costs an impression.
        val now = System.currentTimeMillis()
        if (!force && news.isNotEmpty() && now - lastLoad < 5 * 60_000) return
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
        // Never .getOrNull() here. A swallowed exception is the difference
        // between "the key was rotated" and "there is no network", and both
        // render as the same grey line of text.
        val attempt = runCatching { client.feed(config) }
        attempt.exceptionOrNull()?.let {
            android.util.Log.w("TomSenseFeed", "news fetch failed for ${config.baseUrl}", it)
        }
        val result = attempt.getOrNull()
        loading = false

        if (result == null) {
            error = if (news.isEmpty()) {
                val reason = attempt.exceptionOrNull()?.message?.take(80)
                if (reason.isNullOrBlank()) "Couldn't reach the news feed." else "News feed: $reason"
            } else {
                null
            }
            return
        }
        news = result.items
        insights = insights.copy(feed = feedStats(result.items))

        // Images after the text is already on screen: a card with a title is
        // useful, a card waiting for a thumbnail is not.
        loadThumbs(application, client, config, result.items)

        // Last, and rate-limited: it is the only part of the panel that costs
        // a model call, and it is the least important thing on screen.
        writeSummary(application)
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

    // Calendar, device, weather and feed stats now live in Glance.kt — the
    // panel gathers them, it does not also parse them.

    /**
     * One short written line over everything already gathered.
     *
     * Deliberately last and deliberately throttled. It is the only thing in
     * the panel that spends money, the panel can be opened dozens of times an
     * hour, and nothing below it depends on the result — so a stale summary or
     * none at all is the correct failure mode. No tools are offered: this is a
     * rewrite of facts already in hand, not a turn that should go and act.
     */
    private suspend fun writeSummary(application: TomsenseApp) {
        val now = System.currentTimeMillis()
        if (now - lastSummary < SUMMARY_INTERVAL_MS) return

        val facts = buildList {
            calendar.take(3).forEach { add("Calendar: ${it.title} at ${it.start}") }
            insights.device?.let { add("Device: $it") }
            insights.weather?.let { add("Weather: $it") }
            insights.feed?.let { add("News: $it") }
            news.take(3).forEach { add("Headline: ${it.title}") }
        }
        if (facts.isEmpty()) return
        lastSummary = now

        val prompt = "Write ONE short sentence summarising this person's next few " +
            "hours. No greeting, no preamble, no list. Facts:\n" + facts.joinToString("\n")

        runCatching {
            val text = StringBuilder()
            application.chat.stream(
                ChatRequest(
                    conversationId = SUMMARY_CONV,
                    messages = listOf(WireMessage("user", prompt)),
                ),
            ).collect { event ->
                event.text?.let { text.append(it) }
                event.content?.let { text.clear().append(it) }
            }
            insights = insights.copy(summary = text.toString().trim().lines().firstOrNull().orEmpty())
        }.onFailure {
            // A missing summary is not worth surfacing — every other line in
            // the card is already on screen.
            android.util.Log.d("TomSenseFeed", "glance summary skipped", it)
        }
    }

    /**
     * Answer in the panel rather than launching the app.
     *
     * The reply streams in so the card fills as the model writes, the same way
     * the chat screen does — a panel that sits blank until a full answer
     * arrives reads as broken at exactly the moment it is being judged.
     */
    fun ask() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val application = app ?: return

        draft = ""
        reply = ""
        asking = true
        turn?.cancel()
        turn = CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val active = runner ?: TurnRunner(app = application).also { runner = it }
                val conversation = panelConvId
                    ?: application.repo.createConversation().also { panelConvId = it }
                active.send(conversation, text, onText = { reply = it })
            }.onFailure {
                android.util.Log.w("TomSenseFeed", "panel turn failed", it)
                reply = "Couldn't answer: " + (it.message ?: "unknown error")
            }
            asking = false
        }
    }

    /** Continue the panel's thread in the app, where it can be scrolled. */
    /**
     * Deliberate refresh, bypassing the refetch cooldown.
     *
     * The cooldown exists so opening the panel repeatedly does not spend an
     * impression for the same snapshot; asking for new stories out loud is
     * exactly the case it should not apply to.
     */
    fun refresh() {
        val application = app ?: return
        if (loading) return
        CoroutineScope(Dispatchers.Main).launch { load(application, force = true) }
    }

    fun openAsked() {
        app?.let { onOpenApp(Launch.intent(it)) }
    }

    fun clearReply() {
        turn?.cancel()
        asking = false
        reply = ""
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

    private companion object {
        /**
         * Twice an hour at most. The panel is opened far more often than the
         * facts behind the summary change, and every regeneration is billed.
         */
        const val SUMMARY_INTERVAL_MS = 30 * 60 * 1000L

        /**
         * A fixed id, so the glance line never creates a conversation and
         * never appears in the user's chat list.
         */
        const val SUMMARY_CONV = "feed-glance"
    }
}
