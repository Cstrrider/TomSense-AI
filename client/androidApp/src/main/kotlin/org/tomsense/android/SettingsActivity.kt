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
import org.tomsense.android.ui.TomsenseTheme
import org.tomsense.sync.UsageToday
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
            TomsenseTheme {
                var providers by remember { mutableStateOf<List<ProviderView>>(emptyList()) }
                var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
                var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
                var defaultModel by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var adding by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }
                var prefs by remember { mutableStateOf(UserPrefs()) }
                var imageModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
                var usage by remember { mutableStateOf<UsageToday?>(null) }
                var ttsVoices by remember { mutableStateOf<List<String>>(emptyList()) }

                suspend fun refresh() {
                    runCatching {
                        providers = app.providers.list()
                        val m = app.providers.models()
                        models = m.models
                        imageModels = m.imageModels
                        ttsVoices = m.ttsVoices
                        presets = m.presets
                        defaultModel = m.defaultModel
                        prefs = app.providers.prefs()
                        usage = app.providers.usage()
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

                        usage?.let { u ->
                            item { SectionHeader("Today") }
                            item { UsageCard(u) }
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
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
                                // The image slot picks from text-to-image
                                // models; every other slot from chat models.
                                models = if (slot.key == "image") imageModels else models,
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
                        item { SectionHeader("Home screen feed") }
                        item {
                            Text(
                                "Shows left of the home screen in Lawnchair. Enable " +
                                    "Debug menu \u2192 Ignore feed whitelist, then Home screen " +
                                    "\u2192 Feed provider \u2192 TomSense.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item { NewsSourceCard() }

                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item { SectionHeader("Voice") }

                        item {
                            Text(
                                "Speech in always uses the phone, which costs nothing and works " +
                                    "offline. Speech OUT can use a better voice at the price of a " +
                                    "round trip.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        item {
                            VoiceRow(
                                current = prefs.ttsVoice,
                                voices = ttsVoices,
                                onPick = { v ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            prefs = app.providers.setPrefs(UpdatePrefs(ttsVoice = v))
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
                                models = mine,
                                query = query,
                                // A search forces every card with a hit open,
                                // and fades the ones without.
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
                                onDiscover = {
                                    app.providers.discover(
                                        org.tomsense.sync.DiscoverRequest(providerId = p.id),
                                    ).models
                                },
                                onSetModels = { ids ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            app.providers.update(
                                                p.id,
                                                UpdateProvider(
                                                    models = ids.map {
                                                        org.tomsense.sync.WireModel(id = it)
                                                    },
                                                ),
                                            )
                                            refresh()
                                        }.onFailure { error = it.message }
                                    }
                                },
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
private fun ModelRow(
    id: String,
    caps: ModelOption?,
    included: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = included, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(shortModelName(id), style = MaterialTheme.typography.bodyMedium)
            // Capabilities are only known for models the edge already reports,
            // i.e. ones that are configured. A freshly discovered id has none
            // yet, and inventing tags for it would be a guess presented as fact.
            val tags = buildList {
                if (caps?.vision == true) add("vision")
                if (caps?.reasoning == true) add("reasoning")
                caps?.context?.let { add("${it / 1000}k") }
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * A provider, with its model list folded inside it.
 *
 * The expanded section is for ONE job: choosing which models this provider
 * contributes. It deliberately does not set the default — that lives in
 * Routing, alongside the other slots, because "which model answers" is a
 * routing question and mixing it in here gave a single row two meanings
 * (tap = make default, tick = include) that were easy to confuse.
 *
 * Add and remove happen in place rather than in a dialog. A dialog for this
 * was a modal layer over a list to reach a list, and it hid the provider whose
 * models were being edited.
 *
 * Collapsed by default, because most visits are looking for a provider rather
 * than a model, and an expanded OpenRouter is 300 rows.
 */
@Composable
private fun ProviderCard(
    provider: ProviderView,
    models: List<ModelOption>,
    query: String,
    forceExpanded: Boolean,
    dimmed: Boolean,
    onToggle: (Boolean) -> Unit,
    onDiscover: suspend () -> List<String>,
    onSetModels: (List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // A search overrides the collapsed state but does not destroy it: clearing
    // the query returns each card to however the user left it.
    val expanded = open || forceExpanded
    // Never fade a card the user opened themselves — they may be looking at
    // discovered models the outer search knows nothing about.
    val faded = dimmed && !open

    val configured = provider.models.map { it.id }
    val capsById = models.associateBy { it.value.substringAfter("::") }

    // Configured first, then anything discovery turned up that is not already
    // in use — so the models being relied on stay at the top where they can be
    // unticked, rather than lost among hundreds.
    val union = configured + available.filterNot { it in configured }
    val shown = if (query.isBlank()) {
        union
    } else {
        union.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Card(Modifier.fillMaxWidth().alpha(if (faded) 0.4f else 1f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { open = !open }) {
                    Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                    val status = when {
                        // Cloudflare's "no key" is correct, not a missing setup step.
                        provider.keyless -> "no key needed · ${configured.size} models"
                        provider.hasKey -> "key set · ${configured.size} models"
                        else -> "no key — add one to use this provider"
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall)
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
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !fetching,
                        onClick = {
                            fetching = true
                            note = null
                            scope.launch {
                                val found = runCatching { onDiscover() }.getOrDefault(emptyList())
                                available = found
                                fetching = false
                                note = if (found.isEmpty()) {
                                    "Nothing returned — add ids by hand below."
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

                if (shown.isEmpty()) {
                    Text(
                        if (union.isEmpty()) {
                            "No models yet — fetch the available list, or add an id by hand."
                        } else {
                            "Nothing here matches \"$query\"."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                // Capped for the same reason the dialog was: OpenRouter returns
                // 300+, and composing them all inside a scrolling list janks.
                shown.take(60).forEach { id ->
                    ModelRow(
                        id = id,
                        caps = capsById[id],
                        included = id in configured,
                        onToggle = { include ->
                            // Applied immediately, like the enable switch just
                            // above it. A Save button here would be the only
                            // control on this screen that defers.
                            val next = if (include) configured + id else configured - id
                            onSetModels(next.distinct().sorted())
                        },
                    )
                }
                if (shown.size > 60) {
                    Text(
                        "…${shown.size - 60} more — narrow the search above",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // Always available, not only when discovery fails: a provider
                // can serve a model it does not advertise, and Cloudflare ships
                // new ones faster than the bundled catalogue is regenerated.
                OutlinedTextField(
                    manual,
                    { manual = it },
                    label = { Text("Add model ID by hand") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Row {
                    if (manual.isNotBlank()) {
                        TextButton(onClick = {
                            val id = manual.trim()
                            available = (available + id).distinct()
                            onSetModels((configured + id).distinct().sorted())
                            manual = ""
                        }) { Text("Add \"${manual.trim()}\"") }
                    }
                    if (!provider.builtin) {
                        TextButton(onClick = onDelete) { Text("Delete provider") }
                    }
                }

                if (provider.builtin && configured.isEmpty()) {
                    Text(
                        "With nothing ticked, Cloudflare offers its whole catalogue. " +
                            "Switch the provider off instead if you want none of it.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
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
        "image",
        "Image",
        "Draws pictures when you ask for one. Editing needs a flux-2 model; " +
            "the others can only generate from scratch.",
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
    "image" -> image
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


/**
 * Today's spend.
 *
 * The neuron figure is labelled honestly. Cloudflare reports neurons only at
 * the account level, so without an analytics token this is DERIVED from cost
 * at the published rate — presenting an estimate as a measurement is the kind
 * of thing that gets believed and then acted on.
 */
@Composable
private fun UsageCard(u: UsageToday) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            val pct = if (u.neuronLimit > 0) {
                (u.neurons * 100 / u.neuronLimit).coerceAtMost(999)
            } else {
                0
            }
            Text(
                "${u.neurons} neurons · $pct% of the free daily ${u.neuronLimit}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (u.neuronsMeasured) {
                    "Measured from Cloudflare analytics."
                } else {
                    "Estimated from token cost — add an analytics token under Budget mode for the real figure."
                },
                style = MaterialTheme.typography.labelSmall,
            )

            Text(
                "${u.tokensIn} in · ${u.tokensOut} out" +
                    (if (u.cacheRead > 0) " · ${u.cacheRead} cached" else "") +
                    " · ${u.requests} requests · ${formatUsdSettings(u.costUsd)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )

            u.byModel.take(6).forEach { m ->
                Text(
                    "  ${m.modelId.substringAfterLast('/')} — ${m.tokensIn}/${m.tokensOut}" +
                        (m.costUsd?.let { " · ${formatUsdSettings(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatUsdSettings(usd: Double): String {
    if (usd <= 0) return "\$0"
    if (usd < 0.0001) return "<\$0.0001"
    val q = (usd * 10000).toInt()
    return "\$" + (q / 10000) + "." + (q % 10000).toString().padStart(4, '0')
}


/**
 * Which engine speaks.
 *
 * "Phone" is first and is the default: no network, no cost, works offline. The
 * aura voices sound better and are worth the round trip for anyone who listens
 * to replies rather than reading them, which is exactly who turns this on.
 */
@Composable
private fun VoiceRow(current: String, voices: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Spoken replies", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (current.isBlank()) {
                    "Phone voice — offline, instant, free"
                } else {
                    "aura-2 · $current"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box {
            TextButton(onClick = { open = true }) {
                Text(if (current.isBlank()) "Phone" else current)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Phone voice") },
                    onClick = { open = false; onPick("") },
                )
                voices.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v) },
                        onClick = { open = false; onPick(v) },
                    )
                }
            }
        }
    }
}


/**
 * Where the news comes from.
 *
 * The key is write-only in the UI for the same reason provider keys are: it is
 * shown as set or not set, never echoed back. It is stored on the device
 * rather than at the edge because the panel talks to news-worker directly —
 * routing it through TomSense would put a second hop in front of a feed that
 * already caches locally.
 */
@Composable
private fun NewsSourceCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = org.tomsense.android.feed.NewsClient.config(context)
        baseUrl = cfg.baseUrl
        configured = cfg.isComplete
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saved = false },
            label = { Text("News worker URL") },
            placeholder = { Text("https://news-worker.example.workers.dev") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text(if (configured) "API key (set \u2014 type to replace)" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = baseUrl.isNotBlank() && (apiKey.isNotBlank() || configured),
                onClick = {
                    scope.launch {
                        val existing = org.tomsense.android.feed.NewsClient.config(context)
                        org.tomsense.android.feed.NewsClient.save(
                            context,
                            baseUrl,
                            // Blank means "leave it alone", matching how an
                            // omitted provider key behaves at the edge.
                            apiKey.ifBlank { existing.apiKey },
                        )
                        apiKey = ""
                        configured = true
                        saved = true
                    }
                },
            ) { Text("Save") }
            if (saved) {
                Text("Saved", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
