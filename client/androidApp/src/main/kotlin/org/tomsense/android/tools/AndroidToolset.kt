package org.tomsense.android.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tomsense.tools.DeviceTool
import org.tomsense.tools.Toolset
import org.tomsense.tools.errorJson
import org.tomsense.tools.name
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Every device tool the Android client can run.
 *
 * Two rules shape the whole set:
 *
 * ANYTHING IRREVERSIBLE OR OUTBOUND GOES THROUGH SYSTEM UI. Calls and texts
 * open the dialer and the messaging app pre-filled rather than dialling and
 * sending directly. That is not timidity about permissions — it means a model
 * that hallucinates a phone number cannot place the call, because a human
 * still has to press the button. It also lets the app ship without
 * CALL_PHONE or SEND_SMS at all, so a compromise of this app cannot obtain
 * them either.
 *
 * A FAILED TOOL RETURNS, IT DOES NOT THROW. A denied permission or a missing
 * app is information the model can act on ("I need calendar access") whereas
 * an exception is a dead run. [call] enforces that even for bugs.
 */
class AndroidToolset(context: Context) : Toolset {

    private val app = context.applicationContext

    override val tools: List<DeviceTool> = buildList {
        addAll(intentTools(app))
        addAll(systemTools(app))
        addAll(contentTools(app))
        addAll(healthTools(app))
    }

    private val byName = tools.associateBy { it.name }

    override suspend fun call(name: String, args: JsonObject): String {
        val tool = byName[name] ?: return errorJson("unknown tool $name")
        return try {
            tool.execute(args)
        } catch (e: SecurityException) {
            // Reached when a permission was revoked between the check and the
            // call, which happens for real when the user is in Settings.
            errorJson("permission denied for $name: ${e.message}")
        } catch (e: Exception) {
            errorJson("$name failed: ${e::class.simpleName}: ${e.message}")
        }
    }
}

// ─── argument access ─────────────────────────────────────────────────────────
//
// Models supply arguments loosely: numbers as strings, missing optionals,
// occasionally the wrong type entirely. Every accessor below tolerates that
// and returns null rather than throwing, because a malformed argument should
// produce a useful error to the model, not a crash in the client.

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

internal fun JsonObject.int(key: String): Int? {
    val prim = this[key] as? JsonPrimitive ?: return null
    return prim.intOrNull ?: prim.contentOrNullSafe()?.trim()?.toIntOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val prim = this[key] as? JsonPrimitive ?: return null
    return prim.booleanOrNull ?: prim.contentOrNullSafe()?.trim()?.toBooleanStrictOrNull()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonPrimitive && content == "null") null else content

internal fun JsonObject.require(key: String): String =
    str(key) ?: throw IllegalArgumentException("missing required argument '$key'")

// ─── launching ───────────────────────────────────────────────────────────────

/**
 * Start an activity from a non-activity context.
 *
 * NEW_TASK is required outside an Activity, and the resolve check exists
 * because a phone with no dialer or no maps app is a normal configuration —
 * "no app can handle this" is a far better answer for the model than an
 * ActivityNotFoundException.
 */
internal fun Context.launch(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Runtime permissions, asked for at the moment of use.
 *
 * Deliberately not requested up front at first launch. A permission prompt is
 * far easier to say yes to when it arrives attached to a request you just
 * made ("what's on my calendar today?") than as a wall of dialogs before the
 * app has done anything for you.
 *
 * When no activity is in the foreground — a tool call arriving over voice with
 * the screen off — there is nothing to show a dialog on. That returns false
 * rather than silently failing, so the model can say what it needs.
 */
object PermissionGate {

    private var current: WeakReference<ComponentActivity>? = null
    private var launcher: ((Array<String>) -> Unit)? = null
    private var pending: ((Map<String, Boolean>) -> Unit)? = null

    /** Called from onCreate. The launcher must be registered before RESUMED. */
    fun attach(activity: ComponentActivity) {
        current = WeakReference(activity)
        val contract = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            pending?.invoke(result)
            pending = null
        }
        launcher = { perms -> contract.launch(perms) }
    }

    fun detach(activity: Activity) {
        if (current?.get() === activity) {
            current = null
            launcher = null
            pending = null
        }
    }

    suspend fun require(context: Context, vararg permissions: String): Boolean {
        val missing = permissions.filterNot { context.hasPermission(it) }
        if (missing.isEmpty()) return true

        val request = launcher ?: return false
        // A second request while one is outstanding would strand the first
        // continuation forever; the model can simply ask again.
        if (pending != null) return false

        return suspendCancellableCoroutine { cont ->
            pending = { result -> if (cont.isActive) cont.resume(result.values.all { it }) }
            cont.invokeOnCancellation { pending = null }
            request(missing.toTypedArray())
        }
    }
}
