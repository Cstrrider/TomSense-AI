package org.tomsense.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tomsense.android.ui.ThemeSettings
import org.tomsense.sync.McpToken
import org.tomsense.sync.NewSchedule

/*
 * Settings pages for everything beyond models and routing.
 *
 * Each page loads its own data and reports failures inline (the red line at
 * the top), never by silently showing an empty list — "you have no memories"
 * and "couldn't reach the server" must not look the same.
 */

/** One row on the Settings home page. */
@Composable
internal fun SettingsEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared page scaffold: error line, then content. */
@Composable
private fun Page(error: String?, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Run a call, surfacing its failure; returns true on success. */
private fun CoroutineScope.act(onError: (String?) -> Unit, block: suspend () -> Unit, after: suspend () -> Unit = {}) {
    launch {
        runCatching { block() }
            .onSuccess { onError(null); after() }
            .onFailure { onError(it.message ?: "Something went wrong") }
    }
}

// ─── Appearance ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppearancePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun save() = scope.launch { ThemeSettings.save(context) }
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3 < 0.5f }

    Page(null) {
        item { Label("Theme") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeSettings.Mode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = ThemeSettings.mode == mode,
                        onClick = { ThemeSettings.mode = mode; save() },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeSettings.Mode.entries.size),
                    ) { Text(mode.label) }
                }
            }
        }
        item { Label("Colour") }
        item {
            Hint(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    "Wallpaper uses Material You — the same colours as the rest of your phone."
                } else {
                    "Wallpaper colours need Android 12 or newer; a preset is used instead."
                },
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeSettings.Palette.entries.forEach { p ->
                    val seed = remember(p) { ThemeSettings.seedFor(context, p) }
                    val preview = remember(p, seed, dark) {
                        seed?.let { ThemeSettings.generate(it, dark, ThemeSettings.Style.TonalSpot) }
                    }
                    val selected = ThemeSettings.palette == p
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .clickable { ThemeSettings.palette = p; save() }
                                .padding(5.dp)
                                .clip(CircleShape),
                        ) {
                            // Three tones of the scheme it would produce, like the
                            // phone's own Wallpaper & style swatches.
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxWidth().weight(1f).background(preview?.primary ?: Color.Gray))
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    Box(Modifier.weight(1f).fillMaxSize().background(preview?.secondaryContainer ?: Color.LightGray))
                                    Box(Modifier.weight(1f).fillMaxSize().background(preview?.tertiary ?: Color.DarkGray))
                                }
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(20.dp))
                            }
                        }
                        Text(p.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item { Label("Style") }
        item {
            Hint(
                if (ThemeSettings.palette == ThemeSettings.Palette.Wallpaper) {
                    "\"Match system\" uses exactly the palette your phone generated. Any other style rebuilds it from your wallpaper's colour."
                } else {
                    "How the colour is spread across the app — the same styles as your phone's Wallpaper & style settings."
                },
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeSettings.Style.entries
                    .filter { it != ThemeSettings.Style.System || ThemeSettings.palette == ThemeSettings.Palette.Wallpaper }
                    .forEach { st ->
                        val selected = ThemeSettings.style == st ||
                            (st == ThemeSettings.Style.TonalSpot && ThemeSettings.style == ThemeSettings.Style.System &&
                                ThemeSettings.palette != ThemeSettings.Palette.Wallpaper)
                        FilterChip(
                            selected = selected,
                            onClick = { ThemeSettings.style = st; save() },
                            label = { Text(st.label) },
                            shape = CircleShape,
                        )
                    }
            }
        }
        item { Label("Preview") }
        item {
            CardBox {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ).forEach { Box(Modifier.size(36.dp).clip(CircleShape).background(it)) }
                }
                Button(onClick = {}) { Text("Primary button") }
                Surface(shape = RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("A message you sent", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

// ─── Memory ─────────────────────────────────────────────────────────────────

@Composable
internal fun MemoryPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf("") }
    var savedProfile by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<org.tomsense.sync.MemoryItem>>(emptyList()) }
    var auto by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf("") }

    suspend fun load() {
        runCatching {
            profile = app.features.profile(); savedProfile = profile
            val m = app.features.memories(); memories = m.memories; auto = m.auto
        }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Label("About you") }
        item { Hint("Always shown to the assistant. Write whatever you'd want it to know every time.") }
        item {
            OutlinedTextField(
                value = profile,
                onValueChange = { profile = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("e.g. I'm a teacher, I prefer short answers…") },
            )
        }
        if (profile != savedProfile) {
            item {
                Button(onClick = { scope.act({ error = it }, { app.features.setProfile(profile); savedProfile = profile }) }) {
                    Text("Save")
                }
            }
        }
        item { Label("Memories") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learn from conversations", style = MaterialTheme.typography.bodyLarge)
                    Hint("Automatically keep lasting facts you mention. Off: only what you or the assistant save explicitly.")
                }
                Switch(checked = auto, onCheckedChange = { v -> auto = v; scope.act({ error = it }, { app.features.setAutoMemory(v) }) })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = adding,
                    onValueChange = { adding = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Add something to remember") },
                )
                IconButton(
                    enabled = adding.isNotBlank(),
                    onClick = { scope.act({ error = it }, { app.features.addMemory(adding); adding = "" }, { load() }) },
                ) { Icon(Icons.Filled.Add, "Add memory") }
            }
        }
        if (memories.isEmpty()) item { Hint("Nothing remembered yet.") }
        items(memories, key = { it.id }) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // Pinned memories are in every conversation; the rest only when relevant.
                IconButton(onClick = { scope.act({ error = it }, { app.features.pinMemory(m.id, m.pinned == 0) }, { load() }) }) {
                    Icon(
                        if (m.pinned != 0) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (m.pinned != 0) "Unpin" else "Pin — always include",
                        tint = if (m.pinned != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.act({ error = it }, { app.features.deleteMemory(m.id) }, { load() }) }) {
                    Icon(Icons.Filled.Delete, "Forget")
                }
            }
        }
    }
}

// ─── Personas ───────────────────────────────────────────────────────────────

@Composable
internal fun PersonasPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(org.tomsense.sync.PersonasResponse()) }
    var editing by remember { mutableStateOf<org.tomsense.sync.Persona?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun load() { runCatching { data = app.features.personas() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Hint("A persona changes how the assistant talks — its tone, role and focus. One is active at a time, everywhere.") }
        item {
            PersonaRow("None", "The assistant's default voice", selected = data.active.isBlank(), onSelect = {
                scope.act({ error = it }, { app.features.setActivePersona("") }, { load() })
            })
        }
        items(data.personas, key = { it.id }) { p ->
            PersonaRow(p.name, p.prompt, selected = data.active == p.id, onSelect = {
                scope.act({ error = it }, { app.features.setActivePersona(p.id) }, { load() })
            }, onEdit = { editing = p })
        }
        item { OutlinedButton(onClick = { creating = true }) { Text("New persona") } }
    }

    if (creating || editing != null) {
        val p = editing
        var name by remember(p) { mutableStateOf(p?.name ?: "") }
        var prompt by remember(p) { mutableStateOf(p?.prompt ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (p == null) "New persona" else "Edit persona") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("Instructions") }, minLines = 4,
                        placeholder = { Text("You are a patient tutor who explains with examples…") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
                    creating = false; editing = null
                    scope.act({ error = it }, {
                        if (p == null) app.features.addPersona(name, prompt) else app.features.updatePersona(p.id, name, prompt)
                    }, { load() })
                }) { Text("Save") }
            },
            dismissButton = {
                if (p != null) {
                    TextButton(onClick = {
                        editing = null
                        scope.act({ error = it }, { app.features.deletePersona(p.id) }, { load() })
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

@Composable
private fun PersonaRow(name: String, detail: String, selected: Boolean, onSelect: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onEdit?.let { TextButton(onClick = it) { Text("Edit") } }
    }
}

// ─── Projects ───────────────────────────────────────────────────────────────

@Composable
internal fun ProjectsPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<org.tomsense.sync.Project>>(emptyList()) }
    var editing by remember { mutableStateOf<org.tomsense.sync.Project?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun load() { runCatching { projects = app.features.projects() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Hint("Group chats into projects. A project's instructions apply to every chat in it. Move a chat from its ⋮ menu.") }
        if (projects.isEmpty()) item { Hint("No projects yet.") }
        items(projects, key = { it.id }) { p ->
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editing = p }) { Text("Edit") }
                }
                if (p.instructions.isNotBlank()) Hint(p.instructions)
            }
        }
        item { OutlinedButton(onClick = { creating = true }) { Text("New project") } }
    }

    if (creating || editing != null) {
        val p = editing
        var name by remember(p) { mutableStateOf(p?.name ?: "") }
        var instructions by remember(p) { mutableStateOf(p?.instructions ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (p == null) "New project" else "Edit project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions (optional)") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    creating = false; editing = null
                    scope.act({ error = it }, {
                        if (p == null) app.features.addProject(name, instructions) else app.features.updateProject(p.id, name, instructions)
                    }, { load() })
                }) { Text("Save") }
            },
            dismissButton = {
                if (p != null) {
                    TextButton(onClick = {
                        editing = null
                        scope.act({ error = it }, { app.features.deleteProject(p.id) }, { load() })
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

// ─── Starters ───────────────────────────────────────────────────────────────

@Composable
internal fun StartersPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<String>>(emptyList()) }
    var custom by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf("") }

    suspend fun load() { runCatching { app.features.starters().let { list = it.starters; custom = it.custom } }.onFailure { error = it.message } }
    fun save(next: List<String>) = scope.act({ error = it }, { app.features.setStarters(next).let { list = it.starters; custom = it.custom } })
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Hint("Suggestions shown on a new, empty chat. Tap one to send it.") }
        if (!custom) item { Hint("Showing the built-in suggestions. Adding or removing one makes the list yours.") }
        items(list) { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { save(list - s) }) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(adding, { adding = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a starter") })
                IconButton(enabled = adding.isNotBlank(), onClick = { save(list + adding.trim()); adding = "" }) { Icon(Icons.Filled.Add, "Add") }
            }
        }
        if (custom) item { TextButton(onClick = { save(emptyList()) }) { Text("Reset to built-in") } }
    }
}

// ─── Documents ──────────────────────────────────────────────────────────────

@Composable
internal fun DocumentsPage(app: TomsenseApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(org.tomsense.sync.DocumentsResponse()) }
    var uploading by remember { mutableStateOf(false) }

    suspend fun load() { runCatching { data = app.features.documents() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }
    // Indexing finishes in the background; poll while anything is still indexing.
    LaunchedEffect(data) {
        if (data.documents.any { it.status == "indexing" }) {
            kotlinx.coroutines.delay(3_000)
            load()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        uploading = true
        scope.act({ error = it }, {
            val cr = context.contentResolver
            val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "document"
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
            app.features.uploadDocument(bytes, mime, name)
        }, { uploading = false; load() })
    }

    Page(error) {
        item {
            Hint(
                "PDFs, Word and Excel files, text and more. The assistant searches them when you ask about their contents" +
                    if (data.vector) " — by meaning, not just keywords." else ".",
            )
        }
        item {
            Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !uploading) {
                Text(if (uploading) "Uploading…" else "Upload a document")
            }
        }
        if (data.documents.isEmpty()) item { Hint("No documents yet.") }
        items(data.documents, key = { it.id }) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (d.status) {
                            "ready" -> "${formatBytes(d.size)} · ${d.chunks} passages"
                            "indexing" -> "Reading…"
                            else -> "Couldn't read: ${d.error ?: "unknown error"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.act({ error = it }, { app.features.deleteDocument(d.id) }, { load() }) }) {
                    Icon(Icons.Filled.Delete, "Delete")
                }
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "%.1f MB".format(n / 1048576.0)
}

// ─── Schedules ──────────────────────────────────────────────────────────────

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchedulesPage(app: TomsenseApp, onOpenChat: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<org.tomsense.sync.Schedule>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Results arrive as notifications, so ask for the permission here — at the
    // moment it obviously matters — rather than at first launch.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        NotificationPoller.schedule(context)
    }

    suspend fun load() { runCatching { list = app.features.schedules() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item {
            Hint(
                "Prompts that run on their own — a morning briefing, a weekly summary. Results arrive as a notification " +
                    "and in their own chat. They run on the server, so they work with the phone off (but can't use phone tools " +
                    "like your calendar). Checked every 15 minutes.",
            )
        }
        if (list.isEmpty()) item { Hint("No schedules yet.") }
        items(list, key = { it.id }) { s ->
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Hint(describeSchedule(s))
                    }
                    Switch(checked = s.enabled != 0, onCheckedChange = { v ->
                        scope.act({ error = it }, { app.features.setScheduleEnabled(s.id, v) }, { load() })
                    })
                }
                Text(s.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                s.lastError?.let { Text("Last run failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = { scope.act({ error = it }, { app.features.runScheduleNow(s.id) }, { load() }) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(" Run soon")
                    }
                    s.convId?.let { c -> TextButton(onClick = { onOpenChat(c) }) { Text("Results") } }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { scope.act({ error = it }, { app.features.deleteSchedule(s.id) }, { load() }) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { creating = true; ensureNotifications() }) { Text("New schedule") } }
    }

    if (creating) {
        var title by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("daily") }
        var time by remember { mutableStateOf("08:00") }
        var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
        val validTime = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New schedule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("What should it do?") }, minLines = 3,
                        placeholder = { Text("Summarise today's top tech news in five bullets") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("daily" to "Every day", "weekly" to "Some days").forEach { (k, l) ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l) })
                        }
                    }
                    if (kind == "weekly") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DAY_NAMES.forEachIndexed { i, d ->
                                FilterChip(selected = (i + 1) in days, onClick = {
                                    days = if ((i + 1) in days) days - (i + 1) else days + (i + 1)
                                }, label = { Text(d) })
                            }
                        }
                    }
                    OutlinedTextField(time, { time = it }, label = { Text("Time (24h, HH:MM)") }, singleLine = true,
                        isError = !validTime)
                }
            },
            confirmButton = {
                TextButton(enabled = prompt.isNotBlank() && validTime && (kind == "daily" || days.isNotEmpty()), onClick = {
                    creating = false
                    scope.act({ error = it }, {
                        app.features.addSchedule(
                            NewSchedule(
                                title = title.ifBlank { prompt.take(40) },
                                prompt = prompt,
                                kind = kind,
                                timeLocal = time,
                                days = days.sorted().joinToString(","),
                                tz = java.util.TimeZone.getDefault().id,
                            ),
                        )
                    }, { load() })
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

private fun describeSchedule(s: org.tomsense.sync.Schedule): String {
    val time = runCatching {
        java.time.LocalTime.parse(s.timeLocal).format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
    }.getOrDefault(s.timeLocal)
    val whenText = when (s.kind) {
        "weekly" -> s.days.split(",").mapNotNull { it.trim().toIntOrNull()?.let { d -> DAY_NAMES.getOrNull(d - 1) } }.joinToString(", ") + " at $time"
        "once" -> "Once"
        else -> "Every day at $time"
    }
    val next = s.nextRunAt?.takeIf { s.enabled != 0 }?.let {
        " · next " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))
    } ?: ""
    return whenText + next
}

// ─── Connections (MCP) ──────────────────────────────────────────────────────

@Composable
internal fun ConnectionsPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<org.tomsense.sync.McpServer>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<List<String>?>(null) }
    var token by remember { mutableStateOf<McpToken?>(null) }

    suspend fun load() { runCatching { servers = app.features.mcpServers() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Label("Tools from other services") }
        item { Hint("Connect remote MCP servers (https, Streamable HTTP) and their tools become available to the assistant.") }
        added?.let { t -> item { Hint("Connected — ${t.size} tools: ${t.joinToString(", ")}") } }
        if (servers.isEmpty()) item { Hint("No servers connected.") }
        items(servers, key = { it.id }) { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = s.enabled != 0, onCheckedChange = { v -> scope.act({ error = it }, { app.features.setMcpEnabled(s.id, v) }, { load() }) })
                IconButton(onClick = { scope.act({ error = it }, { app.features.deleteMcpServer(s.id) }, { load() }) }) {
                    Icon(Icons.Filled.Delete, "Remove")
                }
            }
        }
        item { OutlinedButton(onClick = { adding = true }) { Text("Connect a server") } }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Label("Use TomSense from other apps") }
        item {
            Hint(
                "TomSense is also an MCP server: connect it to Claude or another MCP client to search your documents " +
                    "and chats and use your memories there. Each token is a revocable device.",
            )
        }
        item {
            val t = token
            if (t == null) {
                OutlinedButton(onClick = { scope.act({ error = it }, { token = app.features.createMcpToken() }) }) { Text("Create MCP token") }
            } else {
                CardBox {
                    Hint("Server URL")
                    Text(t.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Hint("Bearer token — shown once, copy it now")
                    Text(t.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(t.url)) }) { Text("Copy URL") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(t.token)) }) { Text("Copy token") }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var auth by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) adding = false },
            title = { Text("Connect MCP server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true)
                    OutlinedTextField(auth, { auth = it }, label = { Text("Bearer token (optional)") }, singleLine = true)
                    if (busy) Hint("Connecting and listing its tools…")
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && url.startsWith("https://") && url.length > 10, onClick = {
                    busy = true
                    scope.act({ error = it; busy = false }, { added = app.features.addMcpServer(name, url.trim(), auth.trim()) }, {
                        busy = false; adding = false; load()
                    })
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ─── API keys ───────────────────────────────────────────────────────────────

@Composable
internal fun KeysPage(app: TomsenseApp) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<org.tomsense.sync.SecretView>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }

    suspend fun load() { runCatching { list = app.features.secrets() }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { load() }

    Page(error) {
        item { Hint("Keys for third-party services some tools use. Stored encrypted and never shown again after saving.") }
        items(list, key = { it.name }) { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    if (s.description.isNotBlank()) Hint(s.description)
                    Text(if (s.set) "Set" else "Not set", style = MaterialTheme.typography.labelMedium,
                        color = if (s.set) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editing = s.name }) { Text(if (s.set) "Replace" else "Add") }
                if (s.set) {
                    IconButton(onClick = { scope.act({ error = it }, { app.features.setSecret(s.name, "") }, { load() }) }) {
                        Icon(Icons.Filled.Delete, "Remove")
                    }
                }
            }
        }
    }

    editing?.let { name ->
        var value by remember(name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(name) },
            text = {
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    editing = null
                    scope.act({ error = it }, { app.features.setSecret(name, value) }, { load() })
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ─── Health ─────────────────────────────────────────────────────────────────

/**
 * Health Connect access. Also the page Health Connect opens as this app's
 * privacy rationale (see the manifest), so it says plainly what is read and
 * where it goes.
 */
@Composable
internal fun HealthPage(app: TomsenseApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { org.tomsense.android.tools.Health.available(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    suspend fun load() { granted = org.tomsense.android.tools.Health.granted(context) }
    LaunchedEffect(Unit) { load() }
    val request = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { scope.launch { load() } }

    Page(null) {
        item {
            Hint(
                "Lets the assistant answer questions like \"how did I sleep\" or \"how many steps this week\" " +
                    "from Health Connect. Read-only: TomSense never writes health data.",
            )
        }
        item { Label("What is read") }
        item { Hint("Steps, active calories, exercise sessions, sleep, heart rate, resting heart rate and weight.") }
        item { Label("Where it goes") }
        item {
            Hint(
                "Only when you ask a health question: a daily summary (not raw samples) is sent to the AI model " +
                    "answering you, as part of that conversation. It is not used for anything else, and access can be " +
                    "removed at any time in Health Connect.",
            )
        }
        item { HorizontalDivider() }
        if (!available) {
            item { Text("Health Connect isn't available on this phone. Install or update it from the Play Store.", color = MaterialTheme.colorScheme.error) }
        } else {
            item {
                val all = granted.containsAll(org.tomsense.android.tools.Health.PERMISSIONS)
                Text(
                    when {
                        all -> "Access granted."
                        granted.isEmpty() -> "Not connected."
                        else -> "Partly connected (${granted.size} of ${org.tomsense.android.tools.Health.PERMISSIONS.size})."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Button(onClick = { request.launch(org.tomsense.android.tools.Health.PERMISSIONS) }) {
                    Text(if (granted.isEmpty()) "Connect Health Connect" else "Change access")
                }
            }
        }
    }
}
