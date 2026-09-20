package org.tomsense.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tomsense.sync.CreateProvider
import org.tomsense.sync.ModelOption
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
                var query by remember { mutableStateOf("") }

                suspend fun refresh() {
                    runCatching {
                        providers = app.providers.list()
                        val m = app.providers.models()
                        models = m.models
                        presets = m.presets
                        defaultModel = m.defaultModel
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

                        item { SectionHeader("Default model") }

                        // Search matters more than it looks: OpenRouter alone
                        // advertises 300+ models, so an unfiltered radio list
                        // is unusable on a phone.
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Search models") },
                                placeholder = { Text("opus, vision, 70b…") },
                            )
                        }

                        val filtered = models.filter { it.matches(query) }

                        if (models.isEmpty()) {
                            item {
                                Text(
                                    "No usable models. Add a provider with an API key, " +
                                        "or enable Cloudflare.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else if (filtered.isEmpty()) {
                            item {
                                Text(
                                    "No model matches \"$query\" " +
                                        "(${models.size} available)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        items(filtered, key = { it.value }) { m ->
                            ModelRow(
                                model = m,
                                selected = m.value == defaultModel,
                                onSelect = {
                                    defaultModel = m.value
                                    lifecycleScope.launch {
                                        runCatching { app.providers.setDefaultModel(m.value) }
                                            .onFailure { error = it.message }
                                    }
                                },
                            )
                        }

                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item { SectionHeader("Providers") }

                        items(providers, key = { it.id }) { p ->
                            ProviderCard(
                                provider = p,
                                onToggle = { on ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            app.providers.update(p.id, UpdateProvider(enabled = on))
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
private fun ModelRow(model: ModelOption, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(model.label, style = MaterialTheme.typography.bodyMedium)
            val tags = buildList {
                add(model.provider)
                if (model.vision) add("vision")
                if (model.reasoning) add("reasoning")
                model.context?.let { add("${it / 1000}k") }
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderView,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                val status = when {
                    // Cloudflare's "no key" is correct, not a missing setup step.
                    provider.keyless -> "no key needed · ${provider.models.size} models"
                    provider.hasKey -> "key set · ${provider.models.size} models"
                    else -> "no key — add one to use this provider"
                }
                Text(status, style = MaterialTheme.typography.labelSmall)
            }
            Switch(checked = provider.enabled, onCheckedChange = onToggle)
            if (!provider.builtin) {
                TextButton(onClick = onDelete) { Text("Delete") }
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
