package org.tomsense.android.feed

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tomsense.android.Launch
import org.tomsense.android.TomsenseApp
import org.tomsense.android.TurnRunner
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.WireMessage
import org.tomsense.ui.decodeImageBytes
import org.tomsense.ui.MarkdownText
import org.tomsense.android.ui.PromptPill
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.draw.clip
import org.tomsense.android.voice.VoiceController

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
        // All three insets, and imePadding specifically BECAUSE this window is
        // ours. The assistant overlay must not inset for the keyboard — the
        // framework moves that window itself — but this one is a plain window
        // added by hand with SOFT_INPUT_STATE_UNSPECIFIED, so nothing moves it
        // and the ask box would be typed into from behind the keyboard.
        // navigationBarsPadding for the matching reason: full-screen means the
        // gesture bar sits on top of the last news card.
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Laid out like the Pixel's Discover feed: a header, the search
            // pill, an at-a-glance block written straight onto the
            // background, then cards. Wider side margins than a list, because
            // this is a page to read rather than a list to scan.
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PanelHeader(state) }

                // Ask first, because most openings of this panel are a question
                // rather than a reading session — the same reason the Google app
                // puts search at the top.
                item { AskSection(state) }

                item { DateHeadline() }

                if (!state.insights.isEmpty) {
                    item { InsightsCard(state) }
                }

                if (state.calendar.isNotEmpty()) {
                    item { CalendarCard(state) }
                }

                item {
                    SectionLabel(
                        "Your news",
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
                        onRead = { state.rate(item, "read") },
                    )
                }
            }

            state.undoable?.let { (item, _) ->
                UndoBar(
                    label = item.title.take(28).trim() + "… removed",
                    onUndo = { state.undoRate() },
                    onExpire = { state.clearUndo() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
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
private fun PanelHeader(state: FeedPanelState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name opens the app — the same thing tapping "Google" does in
        // the Google app's feed. Its own clickable row, so the settings
        // button beside it keeps its separate target.
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .clickable(onClickLabel = "Open TomSense") { state.openMain() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Text(
            "TomSense",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 10.dp),
        )
        }
        // Where the Google app keeps your account avatar: the way into
        // everything that is not the feed.
        FilledTonalIconButton(onClick = { state.openSettings() }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

/**
 * Ask, and read the answer without leaving the home screen.
 *
 * The turn runs HERE rather than launching the app: handing a question to an
 * activity meant every "what time is my meeting" cost a cold start and a
 * context switch, which is most of the reason to have a panel at all. The
 * reply streams in; the app is one tap away when it needs following up.
 */
@Composable
private fun AskSection(state: FeedPanelState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PromptPill(
            value = state.draft,
            onValueChange = { state.draft = it },
            placeholder = "Ask TomSense",
            think = state.think,
            onThinkChange = { state.think = it },
            listening = state.voice?.phase == VoiceController.Phase.Listening,
            speaking = state.voice?.phase == VoiceController.Phase.Speaking,
            partial = state.voice?.partial.orEmpty(),
            onMic = { state.toggleMic() },
            generating = state.asking,
            onSend = { state.ask() },
            onStop = { state.clearReply() },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        )

        (state.micError ?: state.voice?.error)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (state.asking || state.reply.isNotBlank()) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.asked.isNotBlank()) {
                        Text(
                            state.asked,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.reply.isBlank()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        // Bounded and scrollable, like the assistant overlay.
                        // Left unbounded a long answer grew the card until the
                        // calendar and every news story were pushed off the panel.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            MarkdownText(state.reply)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { state.openAsked() }) { Text("Continue in app") }
                            TextButton(onClick = { state.clearReply() }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        // Recent chats as chips under the pill, where the Google app puts
        // recent searches: continuing a conversation and starting one are
        // the same action. Horizontal so they cost one line, not five.
        if (state.recent.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.recent, key = { it.first }) { (id, title) ->
                    AssistChip(
                        onClick = { state.openChat(id) },
                        shape = CircleShape,
                        label = {
                            Text(
                                title.ifBlank { "New chat" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
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
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Today",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClickLabel = "Open calendar") { state.openCalendar() },
            )
            state.calendar.forEach { entry ->
                // The coloured rule is the Pixel calendar's event marker: it
                // makes each entry a separate thing at a glance.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { state.openCalendar(entry) },
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
/** Today's date as the page headline — the Pixel feed's At a Glance line. */
@Composable
private fun DateHeadline() {
    Text(
        // The locale's own order and words ("Wednesday, September 23" /
        // "mercredi 23 septembre"), not an English pattern.
        java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEMMMMd"),
            ),
        ),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * The things worth knowing that are not news and not the calendar, as one
 * card. Every row is a way INTO the app that owns the fact — weather opens
 * the weather app, the alarm opens the clock — so the panel is a launcher
 * for them as well as a summary.
 *
 * The written summary is the only part that costs a model call, so it is
 * generated at most twice an hour and the card renders fine without it.
 */
@Composable
private fun InsightsCard(state: FeedPanelState) {
    val i = state.insights
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (i.summary.isNotBlank()) {
                Text(
                    i.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            i.weather?.let { InsightRow(Icons.Filled.WbSunny, it, prominent = true) { state.openWeather() } }
            i.outlook?.let { InsightRow(Icons.Filled.WbTwilight, it) { state.openWeather() } }
            i.air?.let { InsightRow(Icons.Filled.Air, it) { state.openWeather() } }
            i.alarm?.let { InsightRow(Icons.Filled.Alarm, it) { state.openAlarms() } }
            i.games.forEach { g -> InsightRow(Icons.Filled.SportsScore, g.text) { state.openGame(g.url) } }
            i.device?.let { InsightRow(Icons.Filled.BatteryFull, it) { state.openBattery() } }
            // Feed balance has no app to open — it describes the list below.
            i.feed?.let { InsightRow(Icons.Filled.Newspaper, it, onClick = null) }
            i.neurons?.let { InsightRow(Icons.Filled.Bolt, it) { state.openUsage() } }
        }
    }
}

/**
 * "Removed — Undo", floating over the list.
 *
 * A snackbar in spirit, hand-rolled because the feed panel is an overlay with
 * no Scaffold to host a real SnackbarHost, and having the two surfaces differ
 * would be worse than either choice.
 *
 * It times out rather than waiting to be dismissed: an undo bar that stays
 * forever becomes furniture, and the offer is only meaningful while the action
 * is still the last thing you did.
 */
@Composable
internal fun UndoBar(
    label: String,
    onUndo: () -> Unit,
    onExpire: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(label) {
        kotlinx.coroutines.delay(6_000)
        onExpire()
    }
    Surface(
        modifier = modifier,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            TextButton(onClick = onUndo) {
                Text(
                    "Undo",
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

/** One glanceable fact: icon, then the fact, at a size meant to be read. */
@Composable
private fun InsightRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    prominent: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(if (prominent) 24.dp else 20.dp),
            tint = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 14.dp).weight(1f),
        )
        // A chevron says "this goes somewhere" — without it a row that
        // launches an app looks exactly like one that does nothing.
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        onRefresh?.let { action ->
            // The spinner replaces the button rather than sitting beside it,
            // so a second tap during a refresh is impossible by construction.
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                // No size override: Material gives an IconButton a 48dp
                // container for a reason, and setting .size(28.dp) on it
                // shrinks the TOUCH TARGET, not just the visuals. The icon
                // stays small; the tappable area does not.
                IconButton(onClick = action) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh news",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Shared with the in-app News tab — the panel is not the only surface. */
@Composable
internal fun NewsCard(
    item: NewsClient.Item,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onLess: () -> Unit,
    onRead: () -> Unit,
) {
    // Discover-style: tonal card, big corner radius, image inset with its
    // own rounded corners, headline in title weight, source and age beneath.
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(8.dp)) {
            thumb?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOfNotNull(item.outlet?.takeIf { it.isNotBlank() }, ago(item.publishedAt))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Vector icons, never emoji: emoji ignore tinting and look
                    // pasted on against Material You. Full 48dp containers —
                    // "less like this" DELETES the story, so it must be the
                    // hardest thing here to hit by accident. Mark-as-read sits
                    // first because it is the one with no opinion attached.
                    IconButton(onClick = onRead) {
                        Icon(
                            Icons.Filled.DoneAll,
                            contentDescription = "Mark as read",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onMore) {
                        Icon(
                            Icons.Outlined.ThumbUp,
                            contentDescription = "More like this",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onLess) {
                        Icon(
                            Icons.Outlined.ThumbDown,
                            contentDescription = "Less like this",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Seconds since epoch -> "5m", "3h", "2d"; null when unknown. */
private fun ago(epochSeconds: Long): String? {
    if (epochSeconds <= 0) return null
    val mins = (System.currentTimeMillis() / 1000 - epochSeconds) / 60
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}

/** Panel state and the actions it can take. Held by the service, not Compose. */
class FeedPanelState(
    private val onOpenApp: (Intent) -> Unit,
    /** Try each intent in order and stop at the first that starts. */
    private val onOpenFirst: (List<Intent>) -> Unit,
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

    /** The question the reply answers, shown above it. */
    var asked by mutableStateOf("")

    /** Think mode for panel questions. Sticky while the service lives, like the app's. */
    var think by mutableStateOf(false)

    /**
     * Speech in and out, built on the first mic tap — most panel opens never
     * speak, and the recogniser and TTS engine are not free to set up.
     * State-backed so the card recomposes when it appears.
     */
    var voice by mutableStateOf<VoiceController?>(null)
        private set
    var micError by mutableStateOf<String?>(null)
        private set

    /** The next reply is spoken: the question arrived by voice. */
    private var spokeLast = false

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
    suspend fun load(application: TomsenseApp, force: Boolean = false) = coroutineScope {
        app = application

        // Local first, always: recent chats and the glance line come from
        // SQLite and device state, so the panel has content before any network
        // call resolves — or if none ever does.
        recent = withContext(Dispatchers.Default) {
            application.db.schemaQueries.conversationList().executeAsList()
                .take(3)
                .map { it.id to it.title }
        }

        val config = NewsClient.config(application)
        val client = NewsClient(application.httpClient)

        // The last feed from disk, so a cold process paints stories at once
        // instead of a blank card until the network answers.
        if (news.isEmpty()) {
            withContext(Dispatchers.IO) { FeedCache.loadFeed(application) }?.let { cached ->
                news = cached.items
                insights = insights.copy(feed = feedStats(cached.items))
                if (config.isComplete) launch { loadThumbs(application, client, config, cached.items, diskOnly = true) }
            }
        }

        // The feed goes FIRST and runs alongside everything below. It used to
        // wait behind calendar → weather → usage → interests → games, five
        // network round trips in a row before the news request even started.
        launch { loadFeed(application, client, config, force) }

        // Each of these degrades to a missing line rather than failing the
        // card, and none depends on another, so they run concurrently. All
        // writes to `insights` happen back on the calling (main) thread, so
        // the copy-then-assign pattern cannot lose an update.
        val glance = listOf(
            launch { calendar = withContext(Dispatchers.Default) { readCalendar(application) } },
            launch {
                val weather = withContext(Dispatchers.Default) { readWeather(application) }
                insights = insights.copy(
                    weather = weather?.now,
                    outlook = weather?.outlook,
                    air = weather?.air,
                )
            },
            launch {
                insights = insights.copy(
                    device = withContext(Dispatchers.Default) { readDevice(application) },
                    alarm = readAlarm(application),
                )
            },
            // Measured neurons only. The edge reads analytics with the user's
            // own token and caches it for two minutes, so opening the panel
            // often is cheap. No token, signed out, or a failed read → no line.
            launch {
                insights = insights.copy(
                    neurons = runCatching { application.providers.usage() }.getOrNull()
                        ?.takeIf { it.neuronsMeasured }
                        ?.let { u ->
                            val pct = if (u.neuronLimit > 0) u.neurons * 100 / u.neuronLimit else 0
                            "%,d neurons today · %d%% of the free %,d".format(u.neurons, pct, u.neuronLimit)
                        },
                )
            },
            // Games depend on the user's news interests; a user without a
            // feed simply gets no game lines.
            launch {
                if (!config.isComplete) return@launch
                val games = withContext(Dispatchers.Default) {
                    val interests = runCatching { client.interests(config) }.getOrDefault(emptyList())
                    readGames(application, interests)
                }
                insights = insights.copy(games = games)
            },
        )

        // Last, and rate-limited: it is the only part of the panel that costs
        // a model call, and it is the least important thing on screen. It
        // needs the glance facts, not the feed, so it does not wait on news.
        glance.joinAll()
        writeSummary(application)
    }

    private suspend fun loadFeed(
        application: TomsenseApp,
        client: NewsClient,
        config: NewsClient.Config,
        force: Boolean,
    ) {
        // The worker's own snapshot lasts ten minutes; refetching faster than
        // that returns the same order and only costs an impression. lastLoad
        // is zero after a cold start, so a disk-painted feed still refreshes.
        val now = System.currentTimeMillis()
        if (!force && news.isNotEmpty() && now - lastLoad < 5 * 60_000) {
            // The feed is fresh, but its thumbnails may not be: closing the
            // panel cancels this whole load, so a quick glance left the rest
            // of the images unfetched — and returning here used to skip them
            // until the next full refresh. Only the missing ones are fetched.
            if (config.isComplete) loadThumbs(application, client, config, news)
            return
        }
        lastLoad = now

        loading = true
        error = null
        if (!config.isComplete) {
            loading = false
            error = "No news source configured."
            return
        }

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
        withContext(Dispatchers.IO) {
            FeedCache.saveFeed(application, result)
            FeedCache.pruneThumbs(application)
        }

        // Images after the text is already on screen: a card with a title is
        // useful, a card waiting for a thumbnail is not.
        loadThumbs(application, client, config, result.items)
    }

    /**
     * Merge on the main thread. Two loaders can run at once (disk paint and
     * live fetch), and a read-modify-write of `thumbs` from two IO threads
     * drops whichever batch lands second.
     */
    private suspend fun addThumbs(batch: List<Pair<String, ImageBitmap>>) =
        withContext(Dispatchers.Main.immediate) { thumbs = thumbs + batch }

    private suspend fun loadThumbs(
        application: TomsenseApp,
        client: NewsClient,
        config: NewsClient.Config,
        items: List<NewsClient.Item>,
        /** Painting a cached feed: the live fetch will do the network part. */
        diskOnly: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        // Every story with an image, not the first twelve.
        val wanted = items.filter { it.imageUrl != null && !thumbs.containsKey(it.id) }

        // Disk first: a thumbnail seen on any earlier open costs no request.
        val onDisk = wanted.mapNotNull { item ->
            FeedCache.loadThumb(application, item.imageUrl!!, THUMB_WIDTH)
                ?.let { decodeImageBytes(it) }
                ?.let { item.id to it }
        }
        if (onDisk.isNotEmpty()) addThumbs(onDisk)
        if (diskOnly) return@withContext
        val missing = wanted.filter { item -> onDisk.none { it.first == item.id } }

        // Six at a time, and PUBLISHED as they land. Six because the images
        // come from one origin and hammering it is how the og:image backfill
        // got itself throttled.
        for (batch in missing.chunked(6)) {
            val fetched = batch.map { item ->
                async {
                    val bytes = runCatching {
                        application.httpClient.get(client.thumbUrl(config, item.imageUrl!!, THUMB_WIDTH)) {
                            header("Authorization", "Bearer ${config.apiKey}")
                        }.body<ByteArray>()
                    }.getOrNull()
                    val bmp = bytes?.let { decodeImageBytes(it) }
                    // Only bytes that decoded: an error body saved here would
                    // be a broken thumbnail forever.
                    if (bmp != null) FeedCache.saveThumb(application, item.imageUrl!!, THUMB_WIDTH, bytes)
                    item.id to bmp
                }
            }.awaitAll()

            val landed = fetched.mapNotNull { (id, bmp) -> bmp?.let { id to it } }
            if (landed.isNotEmpty()) addThumbs(landed)
        }
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

        // Only facts about the day itself. Battery/network and headlines used
        // to be in here too, and the model turned them into guesses about what
        // the person would be doing ("indoors on wifi researching ...").
        val facts = buildList {
            calendar.take(3).forEach { add("Calendar: ${it.title} at ${it.start}") }
            insights.weather?.let { add("Weather now: $it") }
            insights.outlook?.let { add("Today: $it") }
            insights.air?.let { add("Air: $it") }
            insights.alarm?.let { add("Next alarm: $it") }
            insights.games.forEach { add("${it.team}: ${it.text}") }
        }
        if (facts.isEmpty()) return
        lastSummary = now

        val clock = java.text.SimpleDateFormat("EEEE h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        val rules = "You write a one-line glance for a phone home screen. It is $clock. " +
            "Restate the most useful of the given facts in ONE short sentence, addressed " +
            "to the reader as \"you\" or with no subject at all. Use ONLY the facts given. " +
            "Never guess what the reader is doing or will do, never mention the phone, " +
            "battery or network, and never use he/she/they. No greeting, no preamble, no list."

        runCatching {
            val text = StringBuilder()
            application.chat.stream(
                ChatRequest(
                    conversationId = SUMMARY_CONV,
                    messages = listOf(
                        WireMessage("system", rules),
                        WireMessage("user", "Facts:\n" + facts.joinToString("\n")),
                    ),
                    // No persona/profile/memories: memories are matched to the
                    // prompt, and unrelated ones got woven in as today's plans.
                    bare = true,
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
        asked = text
        reply = ""
        asking = true
        turn?.cancel()
        turn = CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val active = runner ?: TurnRunner(app = application).also { runner = it }
                val conversation = panelConvId
                    ?: application.repo.createConversation().also { panelConvId = it }
                val speakThis = spokeLast
                spokeLast = false
                val speaker = voice?.takeIf { speakThis }
                speaker?.beginReply()
                active.send(conversation, text, think = think, onText = {
                    reply = it
                    speaker?.speakStreaming(it)
                })
                speaker?.endReply(reply)
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

    /** Continue the panel's own thread in the app, where it can be scrolled. */
    fun openAsked() {
        // panelConvId, not whichever chat was open last — landing somewhere
        // else after "Open in app" loses the answer just given.
        app?.let { onOpenApp(Launch.intent(it, conversationId = panelConvId)) }
    }

    /**
     * The mic. The panel is a window owned by a Service, so it cannot show the
     * permission dialog — without the grant, say where to give it.
     */
    fun toggleMic() {
        val application = app ?: return
        if (!VoiceController.hasMicPermission(application)) {
            micError = "Open TomSense and tap its mic once to allow the microphone."
            return
        }
        micError = null
        val v = voice ?: VoiceController(
            context = application,
            scope = CoroutineScope(Dispatchers.Main),
            remoteTts = { text ->
                runCatching { application.edge.speak(text, voice?.remoteVoice?.ifBlank { null }) }.getOrNull()
            },
            onFinalTranscript = { heard ->
                spokeLast = true
                draft = heard
                ask()
            },
        ).also { created ->
            voice = created
            CoroutineScope(Dispatchers.Main).launch {
                runCatching { application.providers.prefs() }.getOrNull()?.let { created.remoteVoice = it.ttsVoice }
            }
        }
        v.toggleListening()
    }

    /** Panel closed: no mic left open, no reply talking over the home screen. */
    fun silence() {
        voice?.stopListening()
        voice?.stopSpeaking()
    }

    fun shutdownVoice() {
        voice?.shutdown()
        voice = null
    }

    fun clearReply() {
        turn?.cancel()
        voice?.stopSpeaking()
        asking = false
        asked = ""
        reply = ""
    }

    /**
     * Open the conversation that was tapped.
     *
     * The id used to be discarded, so every recent-chat row opened whichever
     * chat happened to be open last — which on a panel whose whole purpose is
     * picking between them made the list decorative.
     */
    fun openChat(id: String) {
        app?.let { onOpenApp(Launch.intent(it, conversationId = id)) }
    }

    /** The title: straight into the full app, on its News tab. */
    fun openMain() {
        app?.let { onOpenApp(Launch.intent(it, tab = org.tomsense.android.HomeTab.News)) }
    }

    /**
     * Whatever weather app this phone has. There is no standard "show the
     * weather" intent, so: Pixel Weather, then the Google app's weather page,
     * then a web search — the last one always works.
     */
    fun openWeather() = onOpenFirst(
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage("com.google.android.apps.weather"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("dynact://velour/weather/ProxyActivity"))
                .setClassName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.apps.gsa.velour.DynamicActivityTrampoline",
                ),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=weather")),
        ),
    )

    /**
     * The calendar app, at the event's time when there is one. The
     * content://…/time/<ms> URI is the calendar provider's documented way to
     * open "this moment" and every calendar app handles it.
     */
    fun openCalendar(entry: CalendarEntry? = null) {
        val at = entry?.start?.let { startMillis(it) } ?: System.currentTimeMillis()
        onOpenFirst(
            listOf(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/$at")),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            ),
        )
    }

    fun openAlarms() = onOpenFirst(listOf(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)))

    fun openBattery() = onOpenFirst(
        listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        ),
    )

    /** ESPN's game page — the ESPN app claims the link if it is installed. */
    fun openGame(url: String?) {
        url?.let { onOpenFirst(listOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it)))) }
    }

    private fun startMillis(iso: String): Long? =
        runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                java.time.LocalDate.parse(iso.take(10)).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull()

    /** Settings → AI & models, where the full usage card lives. */
    fun openUsage() {
        app?.let {
            onOpenApp(
                Intent(it, org.tomsense.android.SettingsActivity::class.java)
                    .putExtra(org.tomsense.android.EXTRA_PAGE, "models"),
            )
        }
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
                    // "opened", not "click". The Worker's kinds are more,
                    // less, opened, skipped and read — "click" was rejected
                    // every time, so tapping through taught the ranker nothing
                    // for the entire life of this panel.
                    runCatching { NewsClient(application.httpClient).feedback(config, item.id, "opened") }
                        .onFailure { android.util.Log.w("TomSenseFeed", "open feedback failed", it) }
                }
            }
        }
    }

    /**
     * The last removal, offered back.
     *
     * Holds the INDEX as well as the item so undo restores it where it was
     * rather than at the top — a story reappearing somewhere it never sat
     * reads as a new story, not as the one you just took back.
     */
    var undoable by mutableStateOf<Pair<NewsClient.Item, Int>?>(null)
        private set

    fun clearUndo() {
        undoable = null
    }

    /** Put the article back, and take the rating off the record. */
    fun undoRate() {
        val (item, index) = undoable ?: return
        undoable = null
        news = news.toMutableList().also {
            it.add(index.coerceAtMost(it.size), item)
        }
        app?.let { application ->
            CoroutineScope(Dispatchers.IO).launch {
                val config = NewsClient.config(application)
                if (config.isComplete) {
                    runCatching { NewsClient(application.httpClient).undo(config, item.id) }
                        .onFailure { android.util.Log.w("TomSenseFeed", "undo failed", it) }
                }
            }
        }
    }

    fun rate(item: NewsClient.Item, action: String) {
        // Removed from view immediately on anything that clears the article:
        // leaving it on screen while the request flies makes the button look
        // broken. "read" clears it exactly like "less" does — the difference
        // between them is the opinion attached, not the disappearance.
        if (action == "less" || action == "read") {
            undoable = item to news.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            news = news.filterNot { it.id == item.id }
        }
        app?.let { application ->
            CoroutineScope(Dispatchers.IO).launch {
                val config = NewsClient.config(application)
                if (config.isComplete) {
                    // Logged, not swallowed. A rejected rating used to leave no
                    // trace at all, which is how a field-name mismatch went
                    // unnoticed while every button looked like it worked.
                    runCatching { NewsClient(application.httpClient).feedback(config, item.id, action) }
                        .onFailure { android.util.Log.w("TomSenseFeed", "rating '$action' failed", it) }
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

        /** One size for fetch and disk cache alike, so the cache key matches. */
        const val THUMB_WIDTH = 480
    }
}
