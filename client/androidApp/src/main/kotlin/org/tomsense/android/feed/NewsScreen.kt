package org.tomsense.android.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tomsense.android.TomsenseApp

/**
 * The news feed as a full screen in the app.
 *
 * Shares [FeedPanelState] and [NewsCard] with the home-screen panel rather
 * than duplicating them — the ranking, the feedback calls and the thumbnail
 * proxy all behave identically on both surfaces, and only the chrome differs.
 * The panel is a glance; this is the reading view, so cards get the whole
 * height and there is no ask box or recent-chat list competing for it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NewsScreen(
    app: TomsenseApp,
    state: FeedPanelState,
    modifier: Modifier = Modifier,
    /** Opens the drawer, which is now the only way back to Chat. */
    onOpenDrawer: () -> Unit = {},
) {
    // Deliberately not keyed on anything that recomposes: the cooldown inside
    // load() already decides whether this costs a fetch.
    LaunchedEffect(Unit) { state.load(app) }

    // Same auto-hiding bar as the chat screen. News needs chrome now that the
    // bottom Chat/News tabs are gone: the drawer is the way back to Chat, and
    // a surface with no visible way out only works for people who already
    // know the edge-swipe.
    val barScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { Text("News") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            Modifier.padding(horizontal = 14.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { state.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh news")
                        }
                    }
                },
            )
        },
    ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            state.loading && state.news.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.news.isEmpty() -> {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.error ?: "Nothing to read yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { state.openSettings() }) {
                        Text("Set up the news feed")
                    }
                }
            }

            else -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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

                    // A stale-but-present list plus an error is worth saying
                    // out loud; silently showing old cards looks like success.
                    state.error?.let { message ->
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.undoable?.let { (item, _) ->
            UndoBar(
                label = item.title.take(28).trim() + "… removed",
                onUndo = { state.undoRate() },
                onExpire = { state.clearUndo() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
    }
}
