package org.tomsense.android

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.tomsense.data.ChatRepository
import org.tomsense.db.TomsenseDb
import org.tomsense.sync.ChatClient
import org.tomsense.sync.EdgeApi
import org.tomsense.sync.SyncEngine
import java.util.UUID

/**
 * Object graph for the app.
 *
 * Hand-rolled rather than a DI framework: the graph is small, and the thing
 * that matters here is the ORDER — the database and repository must exist
 * before any UI runs, while the sync engine is started after and never
 * awaited. That ordering is the local-first guarantee in code form.
 */
class TomsenseApp : Application() {

    lateinit var db: TomsenseDb
        private set
    lateinit var repo: ChatRepository
        private set
    lateinit var chat: ChatClient
        private set
    lateinit var sync: SyncEngine
        private set

    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        val driver = AndroidSqliteDriver(TomsenseDb.Schema, this, "tomsense.db")
        db = TomsenseDb(driver)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        db.schemaQueries.initSyncState(deviceId)

        repo = ChatRepository(db, deviceId)

        val http = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!
        val token = { prefs.getString(KEY_DEVICE_TOKEN, null) }

        chat = ChatClient(http, baseUrl, token)
        sync = SyncEngine(db, EdgeApi(http, baseUrl, token), scope)

        // Fire-and-forget on purpose. If this threw or blocked, the app would
        // still open and still work offline — which is the requirement.
        sync.start()
    }

    companion object {
        private const val PREFS = "tomsense"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "https://tomsense-edge.tdisarro.workers.dev"
    }
}
