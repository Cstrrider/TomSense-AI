package org.tomsense.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    /** Delete several chats at once (multi-select). Defaults to one at a time. */
    onDeleteMany: (Set<String>) -> Unit = { ids -> ids.forEach(onDelete) },
    /**
     * The platform's back handler, so Back leaves selection mode instead of
     * closing the drawer with a selection still armed. commonMain on Compose
     * 1.7 has no BackHandler of its own, so the host passes one in.
     */
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    // null = all chats. Filtering here rather than in the query keeps the
    // pinned-first order the query already guarantees.
    var projectFilter by remember { mutableStateOf<String?>(null) }
    val shown = if (projectFilter == null) conversations else conversations.filter { it.project_id == projectFilter }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    // Multi-select. Non-empty = selection mode. Only ids that are currently
    // VISIBLE count: switching the project filter or starting a search must
    // never leave hidden chats armed for a delete the user can no longer see.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val visibleIds = shown.map { it.id }.toSet()
    val selected = if (results == null) selection intersect visibleIds else emptySet()
    val selecting = selected.isNotEmpty()
    var confirmMany by remember { mutableStateOf(false) }

    backHandler(selecting) { selection = emptySet() }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                allSelected = selected.size == visibleIds.size,
                onClear = { selection = emptySet() },
                onSelectAll = {
                    selection = if (selected.size == visibleIds.size) emptySet() else visibleIds
                },
                onDelete = { confirmMany = true },
            )
        } else {
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
                selection = selected,
                onToggleSelect = { id ->
                    selection = if (id in selected) selected - id else selected + id
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (confirmMany && selecting) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmMany = false },
            title = { Text(if (n == 1) "Delete 1 chat?" else "Delete $n chats?") },
            text = {
                Text(
                    (if (n == 1) "It and its messages" else "These $n chats and their messages") +
                        " will be removed from every device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMany(selected)
                    selection = emptySet()
                    confirmMany = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMany = false }) { Text("Cancel") }
            },
        )
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
    selection: Set<String>,
    onToggleSelect: (String) -> Unit,
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
                selecting = selection.isNotEmpty(),
                checked = conv.id in selection,
                onToggleSelect = { onToggleSelect(conv.id) },
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

/**
 * One chat in the list.
 *
 * Hand-built rather than a NavigationDrawerItem: that component takes only
 * onClick, and multi-select needs long-press to enter it. The metrics (56dp,
 * full pill, 16/24 insets, 12dp icon gap, labelLarge) are copied from it so
 * the list looks unchanged outside selection mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pinned = conversation.pinned != 0L
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val highlighted = if (selecting) checked else selected

    Row(
        Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(
                // In selection mode a tap toggles, the way every Android list
                // does it — opening a chat mid-selection would lose the set.
                onClick = if (selecting) onToggleSelect else onSelect,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleSelect()
                },
                onLongClickLabel = "Select",
            )
            .padding(start = if (selecting) 4.dp else 16.dp, end = if (selecting) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            // The whole row is the click target; the box is just the indicator.
            Checkbox(checked = checked, onCheckedChange = { onToggleSelect() })
            Spacer(Modifier.width(4.dp))
        } else if (pinned) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Pinned",
                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            // A chat has no title until something names it, and an empty
            // row is unclickable-looking even though it works.
            conversation.title.ifBlank { "New chat" },
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) colors.onSecondaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!selecting) {
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
                    // Long-press is invisible until someone tells you it is
                    // there; this is the discoverable way into the same mode.
                    DropdownMenuItem(
                        text = { Text("Select") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onToggleSelect()
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
        }
    }
}

/** Replaces the "Chats" header while chats are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
        }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(
                Icons.Filled.SelectAll,
                contentDescription = if (allSelected) "Deselect all" else "Select all",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete selected",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
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
