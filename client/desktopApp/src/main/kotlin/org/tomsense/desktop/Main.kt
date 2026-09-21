package org.tomsense.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import app.cash.sqldelight.db.QueryResult
import org.tomsense.data.ChatRepository
import org.tomsense.db.Message
import org.tomsense.db.TomsenseDb
import org.tomsense.sync.ChatClient
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.EdgeApi
import org.tomsense.sync.SyncEngine
import org.tomsense.sync.SyncStatus
import org.tomsense.sync.WireMessage
import org.tomsense.ui.ChatScreen
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Desktop client.
 *
 * Same shared Compose conversation view as Android, same local-first store,
 * same edge. The platform-specific parts are the JDBC driver, the window,
 * and (M5) the global hotkey overlay and tray icon — a system-wide
 * "screenshot and ask" is the desktop analogue of the assistant role.
 */

private val dataDir: File
    get() = File(System.getProperty("user.home"), ".tomsense").apply { mkdirs() }

private class Deps {
    val db: TomsenseDb
    val repo: ChatRepository
    val chat: ChatClient
    val sync: SyncEngine
    private val scope = CoroutineScope(SupervisorJob())

    init {
        val dbFile = File(dataDir, "tomsense.db")
        val fresh = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        // JdbcSqliteDriver manages neither creation nor upgrades the way the
        // Android driver does: it will happily open a database at the wrong
        // version and then throw "no such column" on the first query that
        // touches anything new. So both paths are explicit here, and the
        // version is tracked in SQLite's own `user_version` pragma.
        migrateDesktopDb(driver, fresh)
        db = TomsenseDb(driver)

        val props = loadProps()
        val deviceId = props.getProperty("device_id") ?: UUID.randomUUID().toString().also {
            props.setProperty("device_id", it)
            saveProps(props)
        }
        db.schemaQueries.initSyncState(deviceId)
        repo = ChatRepository(db, deviceId, driver)

        val http = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val baseUrl = props.getProperty("base_url") ?: DEFAULT_BASE_URL
        val token = { props.getProperty("device_token") }

        chat = ChatClient(http, baseUrl, token)
        sync = SyncEngine(db, EdgeApi(http, baseUrl, token), scope, driver)
        sync.start()

        // Background, unawaited — see the same call in TomsenseApp.
        scope.launch { repo.prepareSearchIndex() }
    }

    private fun propsFile() = File(dataDir, "config.properties")

    private fun loadProps(): Properties = Properties().apply {
        propsFile().takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    private fun saveProps(p: Properties) {
        propsFile().outputStream().use { p.store(it, "TomSense desktop") }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://edge.cstrrider.org"
    }
}

/**
 * Create or upgrade the desktop database.
 *
 * The Android driver does this itself; the JDBC one does not, and the failure
 * mode if it is skipped is an upgraded app opening an old database and
 * throwing "no such column" on the first query that touches a new one.
 *
 * `user_version` is SQLite's own per-file integer, which makes it the right
 * place to record this: it travels WITH the database file, so copying or
 * restoring a database cannot separate it from its version number the way a
 * value kept alongside in the properties file could.
 */
private fun migrateDesktopDb(driver: JdbcSqliteDriver, fresh: Boolean) {
    val target = TomsenseDb.Schema.version

    if (fresh) {
        TomsenseDb.Schema.create(driver).value
        setUserVersion(driver, target)
        return
    }

    val current = userVersion(driver)
    if (current < target) {
        TomsenseDb.Schema.migrate(driver, current, target).value
        setUserVersion(driver, target)
    }
}

private fun userVersion(driver: JdbcSqliteDriver): Long =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        parameters = 0,
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
    ).value

private fun setUserVersion(driver: JdbcSqliteDriver, version: Long) {
    // PRAGMA does not accept a bound parameter, so the value is interpolated.
    // It is a Long from our own schema, never user input.
    driver.execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
}

fun main() = application {
    val deps = remember { Deps() }
    val windowState = rememberWindowState(size = DpSize(900.dp, 700.dp))
    val scope = rememberCoroutineScope()

    Window(onCloseRequest = ::exitApplication, title = "TomSense", state = windowState) {
        MaterialTheme {
            var convId by remember { mutableStateOf<String?>(null) }
            val messagesFlow = remember { MutableStateFlow<List<Message>>(emptyList()) }
            val messages by messagesFlow.collectAsState()
            val syncStatus by deps.sync.status.collectAsState()

            LaunchedEffect(Unit) {
                val existing = deps.db.schemaQueries.conversationList().executeAsList()
                val id = existing.firstOrNull()?.id ?: deps.repo.createConversation()
                convId = id
                deps.repo.messages(id).collect { messagesFlow.value = it }
            }

            convId?.let { id ->
                ChatScreen(
                    messages = messages,
                    syncLabel = when (syncStatus) {
                        SyncStatus.Idle -> ""
                        SyncStatus.Syncing -> "syncing"
                        is SyncStatus.Error -> "offline"
                    },
                    onSend = { text ->
                        scope.launch { send(deps, id, text) }
                    },
                )
            }
        }
    }
}

private suspend fun send(deps: Deps, convId: String, text: String) {
    deps.repo.appendMessage(convId, "user", text)
    val assistantId = deps.repo.appendMessage(convId, "assistant", "")

    // The device's own clock goes in front of every request; see
    // deviceSystemPrompt for the failure that made it necessary.
    val history = listOf(WireMessage("system", org.tomsense.data.deviceSystemPrompt())) +
        deps.db.schemaQueries.messagesFor(convId).executeAsList()
            .filter { it.content.isNotBlank() }
            .map { WireMessage(it.role, it.content) }

    val buffer = StringBuilder()
    runCatching {
        deps.chat.stream(ChatRequest(convId, history)).collect { ev ->
            when (ev.type) {
                "text" -> {
                    buffer.append(ev.text.orEmpty())
                    deps.repo.updateStreamingContent(assistantId, buffer.toString())
                }
                "heartbeat" -> Unit
                // `done` is a round boundary now, not the end of the run —
                // only `end` is terminal. Desktop has no tools, so in practice
                // the two arrive together, but finishing on `done` would mark
                // the message complete mid-generation the moment it does not.
                "end" -> deps.repo.finishStreaming(assistantId)
            }
        }
    }.onFailure {
        deps.repo.updateStreamingContent(
            assistantId,
            buffer.toString().ifEmpty { "[offline — will retry]" },
        )
        deps.repo.finishStreaming(assistantId)
    }
}
