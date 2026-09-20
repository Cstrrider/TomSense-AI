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
import androidx.compose.foundation.layout.statusBarsPadding
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
                        if (models.isEmpty()) {
                            item {
                                Text(
                                    "No usable models. Add a provider with an API key, " +
                                        "or enable Cloudflare.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        items(models, key = { it.value }) { m ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    presets: List<Preset>,
    onDismiss: () -> Unit,
    onCreate: (CreateProvider) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("openai-compat") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelIds by remember { mutableStateOf("") }
    var presetMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    // Masked: this is the only place the key is ever visible,
                    // and it is never readable again after saving.
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    modelIds,
                    { modelIds = it },
                    label = { Text("Model IDs (one per line)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    CreateProvider(
                        name = name.trim(),
                        kind = kind,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey,
                        models = modelIds.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { org.tomsense.sync.WireModel(id = it) },
                    ),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
