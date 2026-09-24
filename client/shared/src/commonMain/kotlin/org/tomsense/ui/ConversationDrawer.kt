package org.tomsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tomsense.data.MessageHit
import org.tomsense.data.SearchResults
import org.tomsense.db.Conversation

/**
 * The conversation list (migration doc phase D).
 *
 * Until this existed the app opened whatever conversation was most recent and
 * offered no way to reach any other, so every chat-management feature behind
 * it was unreachable regardless of whether the data layer supported it.
 *
 * Search results REPLACE the list rather than appearing beside it. A drawer
 * showing both is a drawer where it is never obvious which half a tap acts on.
 */
@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    selectedId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    results: SearchResults?,
    onSelect: (String) -> Unit,
    onOpenMessage: (convId: String, msgId: String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** (id, name) of the user's projects. Empty hides the filter row. */
    projects: List<Pair<String, String>> = emptyList(),
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    // null = all chats. Filtering here rather than in the query keeps the
    // pinned-first order the query already guarantees.
    var projectFilter by remember { mutableStateOf<String?>(null) }
    val shown = if (projectFilter == null) conversations else conversations.filter { it.project_id == projectFilter }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chats",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )

        if (projects.isNotEmpty() && results == null) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    androidx.compose.material3.FilterChip(
                        selected = projectFilter == null,
                        onClick = { projectFilter = null },
                        label = { Text("All") },
                    )
                }
                items(projects.size) { i ->
                    val (id, name) = projects[i]
                    androidx.compose.material3.FilterChip(
                        selected = projectFilter == id,
                        onClick = { projectFilter = if (projectFilter == id) null else id },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (results != null) {
            SearchResultList(
                results = results,
                onSelect = onSelect,
                onOpenMessage = onOpenMessage,
                modifier = Modifier.weight(1f),
            )
        } else {
            ConversationList(
                conversations = shown,
                selectedId = selectedId,
                onSelect = onSelect,
                onRenameRequest = { renaming = it },
                onPin = onPin,
                onDeleteRequest = { deleting = it },
                modifier = Modifier.weight(1f),
            )
        }
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(target.id, it)
                renaming = null
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    if (target.title.isBlank()) {
                        "This removes the conversation and its messages from every device."
                    } else {
                        "\"${target.title}\" and its messages will be removed from every device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationList(
    conversations: List<Conversation>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onRenameRequest: (Conversation) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDeleteRequest: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conversations.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No chats yet.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    // The query already returns pinned first; this only finds the boundary so
    // a divider can go there. Re-sorting here would be a second, disagreeing
    // source of truth for the order.
    val pinnedCount = conversations.count { it.pinned != 0L }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(conversations) { index, conv ->
            if (index == pinnedCount && pinnedCount > 0) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            ConversationRow(
                conversation = conv,
                selected = conv.id == selectedId,
                onSelect = { onSelect(conv.id) },
                onRename = { onRenameRequest(conv) },
                onPin = { onPin(conv.id, conv.pinned == 0L) },
                onDelete = { onDeleteRequest(conv) },
            )
        }
    }
}

/** `items` with an index, kept local so the call site above reads cleanly. */
private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<T>,
    crossinline itemContent: @Composable (Int, T) -> Unit,
) = items(items.size) { index -> itemContent(index, items[index]) }

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pinned = conversation.pinned != 0L

    NavigationDrawerItem(
        selected = selected,
        onClick = onSelect,
        icon = if (pinned) {
            { Icon(Icons.Filled.Star, contentDescription = "Pinned") }
        } else {
            null
        },
        label = {
            Text(
                // A chat has no title until something names it, and an empty
                // row is unclickable-looking even though it works.
                conversation.title.ifBlank { "New chat" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        badge = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onPin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun SearchResultList(
    results: SearchResults,
    onSelect: (String) -> Unit,
    onOpenMessage: (convId: String, msgId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No matches.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (results.conversations.isNotEmpty()) {
            item { SectionLabel("Chats") }
            items(results.conversations, key = { "c-" + it.id }) { conv ->
                NavigationDrawerItem(
                    selected = false,
                    onClick = { onSelect(conv.id) },
                    label = {
                        Text(
                            conv.title.ifBlank { "New chat" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        if (results.messages.isNotEmpty()) {
            item { SectionLabel("Messages") }
            items(results.messages, key = { "m-" + it.msgId }) { hit ->
                MessageHitRow(hit = hit, onClick = { onOpenMessage(hit.convId, hit.msgId) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun MessageHitRow(hit: MessageHit, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        label = {
            Column {
                Text(
                    hit.title.ifBlank { "New chat" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hit.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Title") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                // Renaming to nothing looks like a no-op and leaves the row
                // reading "New chat", which is indistinguishable from a fresh
                // one. Better to require a name than to accept a confusing one.
                enabled = text.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
