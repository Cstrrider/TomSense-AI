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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.tomsense.data.ChatRepository
import org.tomsense.db.TomsenseDb
import org.tomsense.sync.ChatClient
import org.tomsense.sync.EdgeApi
import org.tomsense.sync.ProvidersApi
import org.tomsense.sync.SyncEngine
import org.tomsense.android.tools.AndroidToolset
import org.tomsense.tools.Toolset
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
    lateinit var providers: ProvidersApi
        private set
    lateinit var sync: SyncEngine
        private set

    /**
     * Device tools. Built once at startup because enumerating the launcher
     * for `launch_app` is not free, and the set does not change at runtime.
     */
    lateinit var tools: Toolset
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

        repo = ChatRepository(db, deviceId, driver)
        tools = AndroidToolset(this)

        val http = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val url = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!
        // Read prefs on every call rather than capturing the value: the token
        // does not exist at startup on a fresh install, and re-reading is what
        // lets login take effect without restarting the app.
        val token = { prefs.getString(KEY_DEVICE_TOKEN, null) }

        edge = EdgeApi(http, url, token)
        providers = ProvidersApi(http, url, token)
        chat = ChatClient(http, url, token)
        sync = SyncEngine(db, edge, scope, driver)

        // Fire-and-forget on purpose. If this threw or blocked, the app would
        // still open and still work offline — which is the requirement.
        sync.start()

        // Off the main thread, and deliberately not awaited: indexing old
        // messages is a convenience, and nothing about startup may depend on
        // it finishing — or on it succeeding.
        scope.launch { repo.prepareSearchIndex() }
    }

    /** Base URL the client talks to; also what the login flow opens. */
    val baseUrl: String
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!

    /** Also used directly for share links, which are not part of sync. */
    lateinit var edge: EdgeApi
        private set

    /**
     * Redeem a login code off the main thread and persist the token.
     *
     * Deliberately does not surface WHY it failed to the caller — this runs
     * from a deep-link activity reachable by other apps, and a detailed
     * error is a probing oracle.
     */
    fun exchangeAuthCode(code: String, verifier: String, done: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                val result = edge.exchange(code, verifier, "android")
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DEVICE_TOKEN, result.token)
                    .putString(KEY_DEVICE_ID, result.deviceId)
                    .apply()
                // The sync engine captured a token lambda that reads prefs on
                // every call, so it picks this up without a restart.
                sync.syncOnce()
            }.isSuccess
            done(ok)
        }
    }

    companion object {
        private const val PREFS = "tomsense"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BASE_URL = "base_url"
        // The custom domain, not workers.dev — Cloudflare Access can only
        // front a hostname in a zone you control, so login is impossible
        // against the workers.dev address.
        const val DEFAULT_BASE_URL = "https://edge.cstrrider.org"
    }
}
