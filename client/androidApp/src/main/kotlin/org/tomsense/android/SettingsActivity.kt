package org.tomsense.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tomsense.sync.CreateProvider
import org.tomsense.sync.ModelOption
import org.tomsense.sync.PrefsPatch as UpdatePrefs
import org.tomsense.sync.ToolModels
import org.tomsense.sync.UserPrefs
import org.tomsense.sync.Preset
import org.tomsense.sync.ProviderView
import org.tomsense.sync.UpdateProvider

/**
 * Providers and model selection.
 *
 * Cloudflare appears here as one entry among the rest. It is marked keyless
 * because it authenticates through the Workers AI binding rather than a
 * stored credential — that is its only privilege, and it is a security
 * property, not preferential treatment.
 */
class SettingsActivity : ComponentActivity() {

    private val app by lazy { application as TomsenseApp }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var providers by remember { mutableStateOf<List<ProviderView>>(emptyList()) }
                var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
                var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
                var defaultModel by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var adding by remember { mutableStateOf(false) }
                var managing by remember { mutableStateOf<ProviderView?>(null) }
                var query by remember { mutableStateOf("") }
                var prefs by remember { mutableStateOf(UserPrefs()) }

                suspend fun refresh() {
                    runCatching {
                        providers = app.providers.list()
                        val m = app.providers.models()
                        models = m.models
                        presets = m.presets
                        defaultModel = m.defaultModel
                        prefs = app.providers.prefs()
                    }.onFailure { error = it.message }
                }

                LaunchedEffect(Unit) { refresh() }

                Scaffold(
                    modifier = Modifier.statusBarsPadding().imePadding(),
                    topBar = { TopAppBar(title = { Text("Providers & models") }) },
                ) { pad ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(pad),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        error?.let {
                            item { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                        }

                        item { SectionHeader("Routing") }

                        item {
                            Text(
                                "Which model answers depends on the turn. These decide, in this " +
                                    "order — anything left unset falls through to the default.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // The default is routing too — it is the layer every
                        // unset slot falls through to. Keeping it here rather
                        // than only on a provider card also means it stays
                        // visible when its provider is switched off, which is
                        // exactly when a stale default is confusing.
                        item {
                            SlotRow(
                                slot = Slot(
                                    "default",
                                    "Default",
                                    "Answers anything the slots below do not claim.",
                                ),
                                models = models,
                                current = defaultModel.ifBlank { null },
                                allowClear = false,
                                emptyLabel = "First available",
                                onPick = { value ->
                                    defaultModel = value
                                    lifecycleScope.launch {
                                        runCatching { app.providers.setDefaultModel(value) }
                                            .onFailure { error = it.message }
                                    }
                                },
                            )
                        }

                        // Order matters: it mirrors the precedence the edge
                        // actually applies, so reading down the list explains
                        // why a given turn picked a given model.
                        items(SLOTS, key = { it.key }) { slot ->
                            SlotRow(
                                slot = slot,
                                models = models,
                                current = prefs.toolModels.slot(slot.key),
                                onPick = { value ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            prefs = app.providers.setPrefs(
                                                UpdatePrefs(toolModels = mapOf(slot.key to value)),
                                            )
                                        }.onFailure { error = it.message }
                                    }
                                },
                            )
                        }

                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Auto-route hard turns", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "A small model rates each message; harder ones escalate " +
                                            "to a heavier model. Short messages skip the check.",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Switch(
                                    checked = prefs.autoRoute,
                                    onCheckedChange = { on ->
                                        lifecycleScope.launch {
                                            runCatching {
                                                prefs = app.providers.setPrefs(
                                                    UpdatePrefs(autoRoute = on),
                                                )
                                            }.onFailure { error = it.message }
                                        }
                                    },
                                )
                            }
                        }

                        item {
                            BudgetModeCard(
                                configured = prefs.hasAnalyticsKey,
                                onSave = { key, account ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            prefs = app.providers.setPrefs(
                                                UpdatePrefs(
                                                    cfAnalyticsKey = key,
                                                    cfAccountId = account,
                                                ),
                                            )
                                        }.onFailure { error = it.message }
                                    }
                                },
                            )
                        }

                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item { SectionHeader("Providers & models") }

                        // Search matters more than it looks: OpenRouter alone
                        // advertises 300+ models. With the lists now nested in
                        // their provider, a query also EXPANDS the cards that
                        // match — otherwise searching would appear to find
                        // nothing while the hits sat inside collapsed cards.
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Search models") },
                                placeholder = { Text("opus, vision, 70b…") },
                                // Searches what each provider is CONFIGURED to
                                // offer, not everything it could serve. Saying
                                // so is the difference between "that model
                                // doesn't exist" and "I haven't added it yet".
                                supportingText = {
                                    Text("Searches configured models — open a provider to add more.")
                                },
                            )
                        }

                        if (models.isEmpty()) {
                            item {
                                Text(
                                    "No usable models. Add a provider with an API key, " +
                                        "or enable Cloudflare.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        items(providers, key = { it.id }) { p ->
                            val mine = models.filter { it.value.startsWith("${p.id}::") }
                            val matching = mine.filter { it.matches(query) }
                            ProviderCard(
                                provider = p,
                                models = if (query.isBlank()) mine else matching,
                                defaultModel = defaultModel,
                                // A search forces every card with a hit open,
                                // and hides the ones without.
                                forceExpanded = query.isNotBlank() && matching.isNotEmpty(),
                                dimmed = query.isNotBlank() && matching.isEmpty(),
                                onToggle = { on ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            app.providers.update(p.id, UpdateProvider(enabled = on))
                                            refresh()
                                        }.onFailure { error = it.message }
                                    }
                                },
                                onSelectDefault = { value ->
                                    defaultModel = value
                                    lifecycleScope.launch {
                                        runCatching { app.providers.setDefaultModel(value) }
                                            .onFailure { error = it.message }
                                    }
                                },
                                onManageModels = { managing = p },
                                onDelete = {
                                    lifecycleScope.launch {
                                        runCatching {
                                            app.providers.delete(p.id)
                                            refresh()
                                        }.onFailure { error = it.message }
                                    }
                                },
                            )
                        }

                        item {
                            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Add provider")
                            }
                        }
                    }
                }

                managing?.let { target ->
                    ManageModelsDialog(
                        provider = target,
                        onDiscover = {
                            app.providers.discover(
                                org.tomsense.sync.DiscoverRequest(providerId = target.id),
                            ).models
                        },
                        onDismiss = { managing = null },
                        onSave = { ids ->
                            managing = null
                            lifecycleScope.launch {
                                runCatching {
                                    app.providers.update(
                                        target.id,
                                        UpdateProvider(
                                            models = ids.map { org.tomsense.sync.WireModel(id = it) },
                                        ),
                                    )
                                    refresh()
                                }.onFailure { error = it.message }
                            }
                        },
                    )
                }

                if (adding) {
                    AddProviderDialog(
                        presets = presets,
                        onDiscover = { baseUrl, apiKey ->
                            app.providers.discover(
                                org.tomsense.sync.DiscoverRequest(
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                ),
                            ).models
                        },
                        onDismiss = { adding = false },
                        onCreate = { req ->
                            adding = false
                            lifecycleScope.launch {
                                runCatching {
                                    app.providers.create(req)
                                    refresh()
                                }.onFailure { error = it.message }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Match a model against the search box.
 *
 * Searches the provider name and capability tags as well as the id, so
 * "vision", "anthropic" and "opus" all narrow usefully. Terms are ANDed, so
 * "claude vision" works the way people expect.
 */
private fun ModelOption.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val haystack = buildString {
        append(label).append(' ').append(provider)
        if (vision) append(" vision")
        if (reasoning) append(" reasoning")
    }.lowercase()
    return q.lowercase().split(' ').filter { it.isNotEmpty() }.all { haystack.contains(it) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ModelRow(model: ModelOption, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(model.label, style = MaterialTheme.typography.bodyMedium)
            // The provider name is no longer a tag — the row now sits inside
            // that provider's card, so repeating it is noise.
            val tags = buildList {
                if (model.vision) add("vision")
                if (model.reasoning) add("reasoning")
                model.context?.let { add("${it / 1000}k") }
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * A provider, with its models folded inside it.
 *
 * The models used to be one flat list of everything across every provider,
 * above a separate list of providers. That made two things awkward at once:
 * which provider a model came from was a tag rather than its location, and the
 * only way to reach a provider's own settings was to scroll past every model
 * in the account. Nesting them puts a provider's key, its enabled state, its
 * model list and the default it supplies in one place.
 *
 * Collapsed by default because most of the time you are looking for a
 * provider, not a model — and an expanded OpenRouter is 300 rows.
 */
@Composable
private fun ProviderCard(
    provider: ProviderView,
    models: List<ModelOption>,
    defaultModel: String,
    forceExpanded: Boolean,
    dimmed: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectDefault: (String) -> Unit,
    onManageModels: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // A search overrides the manual state, but does not destroy it: clearing
    // the query returns each card to however the user had left it.
    val expanded = open || forceExpanded

    val holdsDefault = models.any { it.value == defaultModel }

    Card(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.4f else 1f),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable { open = !open },
                ) {
                    Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                    val status = when {
                        // Cloudflare's "no key" is correct, not a missing setup step.
                        provider.keyless -> "no key needed · ${provider.models.size} models"
                        provider.hasKey -> "key set · ${provider.models.size} models"
                        else -> "no key — add one to use this provider"
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall)

                    // Surfaced while collapsed, so the default model is
                    // findable without opening every card to hunt for it.
                    if (holdsDefault && !expanded) {
                        Text(
                            "default: ${shortModelName(defaultModel)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { open = !open }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide models" else "Show models",
                    )
                }
                Switch(checked = provider.enabled, onCheckedChange = onToggle)
            }

            if (expanded) {
                if (models.isEmpty()) {
                    Text(
                        if (provider.enabled) {
                            "No models configured. Add some below."
                        } else {
                            "Provider is off — its models are hidden from the picker."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Text(
                        "Tap a model to make it the default.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    models.forEach { m ->
                        ModelRow(
                            model = m,
                            selected = m.value == defaultModel,
                            onSelect = { onSelectDefault(m.value) },
                        )
                    }
                }

                Row {
                    TextButton(onClick = onManageModels) { Text("Add or remove models") }
                    if (!provider.builtin) {
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
            }
        }
    }
}

/**
 * Add or remove the models a provider offers, after it has been created.
 *
 * This exists because model discovery used to happen ONLY while adding a
 * provider, which froze the list at that moment. The search box on the main
 * screen then searched that frozen list — so it found what was already there
 * rather than what the provider actually offers, and a model added by the
 * provider later was unreachable without deleting and re-adding the whole
 * thing (losing the API key with it).
 *
 * The list shown is the union of what is configured and what discovery
 * returned, so the current selection is always visible and never silently
 * dropped by a filter or by a provider that has stopped advertising a model
 * the user still relies on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageModelsDialog(
    provider: ProviderView,
    onDiscover: suspend () -> List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(provider.models.map { it.id }.toSet()) }
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Configured first, then anything discovered that is not already
    // configured — so the models in use stay at the top where they can be
    // unticked, instead of being lost in a list of hundreds.
    val union = remember(available, chosen) {
        val configured = provider.models.map { it.id }
        configured + available.filterNot { it in configured }
    }
    val shown = union.filter { it.contains(filter.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.name) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !fetching,
                        onClick = {
                            fetching = true
                            note = null
                            scope.launch {
                                val found = runCatching { onDiscover() }.getOrDefault(emptyList())
                                available = found
                                fetching = false
                                note = if (found.isEmpty()) {
                                    "Nothing returned — this provider may not list models. Add ids by hand below."
                                } else {
                                    "${found.size} available"
                                }
                            }
                        },
                    ) { Text(if (fetching) "Fetching…" else "Fetch available") }

                    note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Text(
                    "${chosen.size} selected of ${union.size}",
                    style = MaterialTheme.typography.labelSmall,
                )

                if (union.size > 8) {
                    OutlinedTextField(
                        filter,
                        { filter = it },
                        singleLine = true,
                        label = { Text("Filter") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Capped for the same reason as the add dialog: OpenRouter
                // returns 300+ and composing them all inside a dialog janks.
                shown.take(60).forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in chosen,
                            onCheckedChange = {
                                chosen = if (id in chosen) chosen - id else chosen + id
                            },
                        )
                        Text(id, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (shown.size > 60) {
                    Text(
                        "…${shown.size - 60} more — narrow the filter",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // Always available, not just when discovery fails: a provider
                // can serve a model it does not advertise, and Cloudflare
                // ships new ones faster than the bundled catalogue is
                // regenerated.
                OutlinedTextField(
                    manual,
                    { manual = it },
                    label = { Text("Add model ID by hand") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (manual.isNotBlank()) {
                    TextButton(onClick = {
                        val id = manual.trim()
                        chosen = chosen + id
                        available = (available + id).distinct()
                        manual = ""
                    }) { Text("Add \"${manual.trim()}\"") }
                }

                if (provider.builtin && chosen.isEmpty()) {
                    Text(
                        "With nothing selected, Cloudflare offers its whole catalogue. " +
                            "Turn the provider off instead if you want none of it.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toList().sorted()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add-provider form with model discovery.
 *
 * Ported from stable's `/me/providers/discover`: hit the provider's
 * OpenAI-shaped `/models` endpoint and let the user tick what they want,
 * instead of typing ids from memory. Manual entry stays as the fallback,
 * because plenty of endpoints don't implement `/models`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    presets: List<Preset>,
    onDiscover: suspend (baseUrl: String, apiKey: String) -> List<String>,
    onDismiss: () -> Unit,
    onCreate: (CreateProvider) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("openai-compat") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var presetMenu by remember { mutableStateOf(false) }

    var discovered by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var discovering by remember { mutableStateOf(false) }
    var discoverNote by remember { mutableStateOf<String?>(null) }
    var modelFilter by remember { mutableStateOf("") }
    var manualIds by remember { mutableStateOf("") }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                Row {
                    TextButton(onClick = { presetMenu = true }) { Text("Use a preset") }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        presets.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    name = p.name
                                    kind = p.kind
                                    baseUrl = p.baseUrl
                                    presetMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL (https)") })
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    label = { Text("API key") },
                    // Masked, and this is the only moment it is ever visible —
                    // the server never returns it again.
                    visualTransformation = PasswordVisualTransformation(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !discovering && baseUrl.startsWith("https://"),
                        onClick = {
                            discovering = true
                            discoverNote = null
                            scope.launch {
                                val found = runCatching {
                                    onDiscover(baseUrl.trim(), apiKey)
                                }.getOrDefault(emptyList())
                                discovered = found
                                discovering = false
                                discoverNote = if (found.isEmpty()) {
                                    "No models returned — check the key, or enter ids manually below."
                                } else {
                                    "${found.size} models found"
                                }
                            }
                        },
                    ) { Text(if (discovering) "Fetching…" else "Fetch models") }

                    discoverNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (discovered.isNotEmpty()) {
                    OutlinedTextField(
                        modelFilter,
                        { modelFilter = it },
                        singleLine = true,
                        label = { Text("Filter") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Cap the rendered list: OpenRouter returns 300+ and
                    // composing them all inside a dialog janks badly.
                    val shown = discovered
                        .filter { it.contains(modelFilter.trim(), ignoreCase = true) }
                        .take(60)
                    shown.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id in chosen,
                                onCheckedChange = {
                                    chosen = if (id in chosen) chosen - id else chosen + id
                                },
                            )
                            Text(id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (discovered.size > shown.size) {
                        Text(
                            "…${discovered.size - shown.size} more — narrow the filter",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    OutlinedTextField(
                        manualIds,
                        { manualIds = it },
                        label = { Text("Model IDs (one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ids = if (chosen.isNotEmpty()) {
                    chosen.toList().sorted()
                } else {
                    manualIds.lines().map { it.trim() }.filter { it.isNotEmpty() }
                }
                onCreate(
                    CreateProvider(
                        name = name.trim(),
                        kind = kind,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey,
                        models = ids.map { org.tomsense.sync.WireModel(id = it) },
                    ),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The routing slots, in the order the edge applies them.
 *
 * Listing them in precedence order is deliberate: read down the list and you
 * have the answer to "why did that model reply?", which is the question the
 * whole layered router exists to make answerable.
 */
private data class Slot(val key: String, val label: String, val help: String)

private val SLOTS = listOf(
    Slot(
        "research",
        "Think",
        "Used when think mode is on. A reasoning model earns its keep here.",
    ),
    Slot(
        "vision",
        "Vision",
        "Owns image turns — even if your chat model can also see images.",
    ),
    Slot(
        "title",
        "Utility",
        "Titles, follow-ups and the auto-route check. Pick something small " +
            "and NON-reasoning: a reasoning model spends the tiny budget " +
            "thinking and returns nothing.",
    ),
    Slot(
        "chat_fallback",
        "Chat fallback",
        "Used if the main model stalls, and where budget mode lands.",
    ),
    Slot("vision_fallback", "Vision fallback", "Used if the Vision model stalls."),
)

private fun ToolModels.slot(key: String): String? = when (key) {
    "research" -> research
    "vision" -> vision
    "title" -> title
    "chat_fallback" -> chatFallback
    "vision_fallback" -> visionFallback
    "title_fallback" -> titleFallback
    else -> null
}

@Composable
private fun SlotRow(
    slot: Slot,
    models: List<ModelOption>,
    current: String?,
    onPick: (String) -> Unit,
    /** False for the default, which has nothing to fall through TO. */
    allowClear: Boolean = true,
    /** Shown when nothing is set. "Default" reads as nonsense on the default row. */
    emptyLabel: String = "Default",
) {
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.bodyMedium)
                Text(slot.help, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                TextButton(onClick = { open = true }) {
                    Text(current?.let { shortModelName(it) } ?: emptyLabel)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    // Clearing is a first-class choice, not the absence of one:
                    // "no slot" is meaningfully different from "some model".
                    if (allowClear) {
                        DropdownMenuItem(
                            text = { Text("Use default") },
                            onClick = {
                                open = false
                                onPick("")
                            },
                        )
                    }
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(shortModelName(m.value), style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                open = false
                                onPick(m.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun shortModelName(spec: String): String =
    spec.substringAfter("::").substringAfterLast('/')

/**
 * Budget mode setup.
 *
 * The token is write-only: the card reports whether one is stored and never
 * shows it, because the server does not return it. Running inference needs no
 * token at all — only READING usage does — which is why this is opt-in rather
 * than something the app requires up front.
 */
@Composable
private fun BudgetModeCard(configured: Boolean, onSave: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Budget mode", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (configured) {
                        "On — heavy Cloudflare models downshift past 80% of the daily free neurons."
                    } else {
                        "Off. Needs a Cloudflare API token with Account Analytics: Read."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (configured) "Change" else "Set up")
            }
        }

        if (expanded) {
            OutlinedTextField(
                account,
                { account = it },
                label = { Text("Cloudflare account ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                key,
                { key = it },
                label = { Text("API token (Analytics: Read)") },
                singleLine = true,
                // Stored encrypted and never returned, so this is the only
                // moment it is ever visible.
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(
                    onClick = {
                        onSave(key.trim(), account.trim())
                        key = ""
                        expanded = false
                    },
                    enabled = key.isNotBlank() && account.isNotBlank(),
                ) { Text("Save") }
                if (configured) {
                    TextButton(onClick = {
                        onSave("", "")
                        key = ""
                        expanded = false
                    }) { Text("Remove") }
                }
            }
        }
    }
}
