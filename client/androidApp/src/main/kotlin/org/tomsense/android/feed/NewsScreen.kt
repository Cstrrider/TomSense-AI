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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
fun NewsScreen(app: TomsenseApp, state: FeedPanelState, modifier: Modifier = Modifier) {
    // Deliberately not keyed on anything that recomposes: the cooldown inside
    // load() already decides whether this costs a fetch.
    LaunchedEffect(Unit) { state.load(app) }

    Box(modifier.fillMaxSize()) {
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

        // Floating rather than in a bar: this screen has no chrome of its own,
        // and a whole app bar for one control would cost more height than the
        // control is worth.
        if (state.loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            FilledTonalIconButton(
                onClick = { state.refresh() },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh news",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
