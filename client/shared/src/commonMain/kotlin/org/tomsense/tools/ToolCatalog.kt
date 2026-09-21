package org.tomsense.tools

/**
 * Every device tool, as the model sees it.
 *
 * This file is the prompt. Descriptions here are not documentation — they are
 * the only thing a model has to decide between `set_reminder` and `set_alarm`
 * at 11pm, so they say what the tool DOES and, where it matters, what it does
 * not. `make_call` promising to place a call when it only opens the dialer
 * would have the model tell the user the phone is ringing.
 *
 * Deliberately free of platform types so the contract can be dumped and run
 * against a live model without an emulator: `./gradlew :shared:dumpToolSchemas`.
 */
object ToolCatalog {

    // ─── opening things ──────────────────────────────────────────────────────

    val OPEN_URL = ToolSpec(
        name = "open_url",
        description = "Open a web page in the user's browser.",
        parameters = objectSchema(
            required = listOf("url"),
            props = mapOf("url" to stringProp("Full URL including https://")),
        ),
    )

    val OPEN_MAPS = ToolSpec(
        name = "open_maps",
        description = "Show a place, address, or search on the map, or start navigation to it.",
        parameters = objectSchema(
            required = listOf("query"),
            props = mapOf(
                "query" to stringProp("Place name, address, or search term"),
                "navigate" to stringProp("Set to 'true' to start turn-by-turn directions"),
            ),
        ),
    )

    val SHARE_TEXT = ToolSpec(
        name = "share_text",
        description = "Open the Android share sheet so the user can send some text elsewhere.",
        parameters = objectSchema(
            required = listOf("text"),
            props = mapOf(
                "text" to stringProp("The text to share"),
                "subject" to stringProp("Optional subject line"),
            ),
        ),
    )

    val LAUNCH_APP = ToolSpec(
        name = "launch_app",
        description = "Open an installed app by its name, e.g. 'Spotify'.",
        parameters = objectSchema(
            required = listOf("app"),
            props = mapOf("app" to stringProp("App name as shown on the home screen")),
        ),
    )

    val OPEN_SETTINGS = ToolSpec(
        name = "open_settings",
        description = "Open a system settings screen so the user can change something themselves.",
        parameters = objectSchema(
            required = listOf("screen"),
            props = mapOf(
                "screen" to stringProp(
                    "Which settings screen to open",
                    enum = SETTINGS_SCREENS,
                ),
            ),
        ),
    )

    // ─── reaching people — both stop short of sending ────────────────────────

    val MAKE_CALL = ToolSpec(
        name = "make_call",
        description =
        "Open the phone dialer with a number filled in. This does NOT place " +
            "the call — the user still has to press the call button, so say " +
            "the dialer is ready rather than that the phone is ringing.",
        parameters = objectSchema(
            required = listOf("number"),
            props = mapOf("number" to stringProp("Phone number to dial")),
        ),
    )

    val SEND_SMS = ToolSpec(
        name = "send_sms",
        description =
        "Open the messaging app with a text message composed and ready. This " +
            "does NOT send it — the user still has to press send, so say the " +
            "message is ready rather than that it has been sent.",
        parameters = objectSchema(
            required = listOf("number", "message"),
            props = mapOf(
                "number" to stringProp("Recipient phone number"),
                "message" to stringProp("Message body"),
            ),
        ),
    )

    val GET_CONTACTS = ToolSpec(
        name = "get_contacts",
        description = "Look up a contact's phone numbers by name.",
        parameters = objectSchema(
            required = listOf("name"),
            props = mapOf(
                "name" to stringProp("Full or partial contact name"),
                "max" to numberProp("Maximum contacts to return. Default 5."),
            ),
        ),
    )

    // ─── time ────────────────────────────────────────────────────────────────

    val START_TIMER = ToolSpec(
        name = "start_timer",
        description = "Start a countdown timer, for a duration from now.",
        parameters = objectSchema(
            required = listOf("seconds"),
            props = mapOf(
                "seconds" to numberProp("Duration in seconds"),
                "label" to stringProp("Optional timer label"),
            ),
        ),
    )

    val SET_ALARM = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm that goes off at a clock time, e.g. 7:30am.",
        parameters = objectSchema(
            required = listOf("hour", "minute"),
            props = mapOf(
                "hour" to numberProp("Hour, 0-23"),
                "minute" to numberProp("Minute, 0-59"),
                "label" to stringProp("Optional alarm label"),
            ),
        ),
    )

    val SET_REMINDER = ToolSpec(
        name = "set_reminder",
        description =
        "Remind the user about a specific thing at a specific time, e.g. " +
            "'remind me to call the dentist at 3pm'. Use set_alarm for a plain " +
            "wake-up and start_timer for a countdown.",
        parameters = objectSchema(
            required = listOf("text", "at"),
            props = mapOf(
                "text" to stringProp("What to be reminded about"),
                "at" to stringProp("When, ISO 8601 local time e.g. 2026-09-21T15:00"),
            ),
        ),
    )

    // ─── calendar ────────────────────────────────────────────────────────────

    val GET_CALENDAR = ToolSpec(
        name = "get_calendar",
        description = "Read the user's upcoming calendar events.",
        parameters = objectSchema(
            props = mapOf(
                "days" to numberProp("How many days ahead to look. Default 1."),
                "max" to numberProp("Maximum events to return. Default 20."),
            ),
        ),
    )

    val CREATE_CALENDAR_EVENT = ToolSpec(
        name = "create_calendar_event",
        description = "Add an event to the user's calendar.",
        parameters = objectSchema(
            required = listOf("title", "start"),
            props = mapOf(
                "title" to stringProp("Event title"),
                "start" to stringProp("Start time, ISO 8601 local e.g. 2026-09-21T14:30"),
                "duration_minutes" to numberProp("Length in minutes. Default 60."),
                "location" to stringProp("Optional location"),
                "description" to stringProp("Optional notes"),
            ),
        ),
    )

    // ─── device state ────────────────────────────────────────────────────────

    val GET_LOCATION = ToolSpec(
        name = "get_location",
        description =
        "Where the user is right now, as coordinates and a street address. " +
            "Use this before answering anything that depends on where they are.",
        parameters = objectSchema(),
    )

    val GET_DEVICE_STATUS = ToolSpec(
        name = "get_device_status",
        description =
        "Battery level, charging state, network connection, volume, and device model.",
        parameters = objectSchema(),
    )

    val SET_VOLUME = ToolSpec(
        name = "set_volume",
        description = "Set media volume as a percentage, 0-100.",
        parameters = objectSchema(
            required = listOf("percent"),
            props = mapOf("percent" to numberProp("Volume 0-100")),
        ),
    )

    val SET_BRIGHTNESS = ToolSpec(
        name = "set_brightness",
        description = "Set screen brightness as a percentage, 0-100.",
        parameters = objectSchema(
            required = listOf("percent"),
            props = mapOf("percent" to numberProp("Brightness 0-100")),
        ),
    )

    // ─── media ───────────────────────────────────────────────────────────────

    val MEDIA_CONTROL = ToolSpec(
        name = "media_control",
        description = "Control whatever is currently playing: pause, skip, resume.",
        parameters = objectSchema(
            required = listOf("action"),
            props = mapOf(
                "action" to stringProp(
                    "What to do",
                    enum = listOf("play", "pause", "toggle", "next", "previous", "stop"),
                ),
            ),
        ),
    )

    val PLAY_MUSIC = ToolSpec(
        name = "play_music",
        description = "Start playing music by artist, album, song, or genre.",
        parameters = objectSchema(
            required = listOf("query"),
            props = mapOf("query" to stringProp("What to play, e.g. 'Miles Davis'")),
        ),
    )

    /** Every spec, in the order they are advertised to the model. */
    val ALL: List<ToolSpec> = listOf(
        GET_LOCATION, GET_CALENDAR, CREATE_CALENDAR_EVENT, SET_REMINDER,
        START_TIMER, SET_ALARM, GET_CONTACTS, MAKE_CALL, SEND_SMS,
        LAUNCH_APP, OPEN_URL, OPEN_MAPS, SHARE_TEXT, OPEN_SETTINGS,
        SET_VOLUME, SET_BRIGHTNESS, MEDIA_CONTROL, GET_DEVICE_STATUS, PLAY_MUSIC,
    )
}

/**
 * Settings screens worth exposing, as an allow-list.
 *
 * An allow-list rather than passing the model's string to Intent() directly:
 * arbitrary actions built from generated text are a way to launch things that
 * were never meant to be reachable this way.
 */
val SETTINGS_SCREENS: List<String> = listOf(
    "wifi", "bluetooth", "display", "sound", "battery", "storage",
    "apps", "location", "airplane", "date", "accessibility", "notifications",
)
