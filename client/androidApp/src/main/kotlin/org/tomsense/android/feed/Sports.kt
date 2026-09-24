package org.tomsense.android.feed

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomsense.android.TomsenseApp

/*
 * Game lines for the teams THIS user follows.
 *
 * Which teams comes from the news feed's declared interests ("Philadelphia
 * Eagles NFL football"), matched against ESPN's team lists. Nothing is baked
 * in: someone who follows no teams gets no game line, and someone who follows
 * three gets three. Everything here is keyless public ESPN data.
 */

/** Leagues checked, as ESPN `sport/league` paths, with words that point at each. */
private val LEAGUES = listOf(
    "football/nfl" to listOf("nfl", "football"),
    "basketball/nba" to listOf("nba", "basketball"),
    "baseball/mlb" to listOf("mlb", "baseball"),
    "hockey/nhl" to listOf("nhl", "hockey"),
    "basketball/wnba" to listOf("wnba"),
    "soccer/usa.1" to listOf("mls", "soccer"),
    "soccer/eng.1" to listOf("premier league", "epl", "football club", "soccer"),
)

@Serializable
private data class Team(
    val league: String,
    val id: String,
    val displayName: String,
    /** Nickname-style aliases ("Eagles", "Bournemouth"), matched with care. */
    val aliases: List<String>,
)

@Serializable
private data class TeamCache(val fetchedAt: Long, val teams: List<Team>)

private const val TEAM_CACHE_MS = 7 * 86_400_000L
private const val MAX_GAMES = 3

/**
 * Every team in [LEAGUES], cached on disk for a week. Rosters of TEAMS barely
 * change, and the full set is ~800 KB — not something to refetch per panel open.
 */
private suspend fun teams(app: TomsenseApp): List<Team> {
    val file = File(app.cacheDir, "espn-teams.json")
    val cached = runCatching { Json.decodeFromString<TeamCache>(file.readText()) }.getOrNull()
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TEAM_CACHE_MS) return cached.teams

    val fresh = LEAGUES.flatMap { (league, _) ->
        runCatching {
            val res = app.httpClient.get("https://site.api.espn.com/apis/site/v2/sports/$league/teams")
            if (!res.status.isSuccess()) return@runCatching emptyList()
            Json.parseToJsonElement(res.body<String>()).jsonObject["sports"]!!.jsonArray[0].jsonObject["leagues"]!!
                .jsonArray[0].jsonObject["teams"]!!.jsonArray.map { it.jsonObject["team"]!!.jsonObject }
                .map { t ->
                    Team(
                        league = league,
                        id = t.str("id"),
                        displayName = t.str("displayName"),
                        aliases = listOf(t.str("name"), t.str("shortDisplayName"))
                            .filter { it.length >= 4 }.distinct(),
                    )
                }
        }.getOrDefault(emptyList())
    }
    // A failed refresh keeps the stale list rather than losing every game line.
    if (fresh.isEmpty()) return cached?.teams.orEmpty()
    runCatching { file.writeText(Json.encodeToString(TeamCache(System.currentTimeMillis(), fresh))) }
    return fresh
}

/**
 * Which teams an interest names.
 *
 * The full name ("Philadelphia Eagles") is always a match. A bare nickname is
 * only trusted when it is unambiguous: "Giants", "Kings", "Cardinals" and
 * "Panthers" each name teams in several leagues, so they match only when the
 * interest also says which sport ("Giants baseball").
 */
private fun matchTeams(text: String, all: List<Team>): List<Team> {
    val t = " " + text.lowercase().replace(Regex("[^a-z0-9. ]"), " ") + " "
    fun has(phrase: String) = t.contains(" " + phrase.lowercase() + " ")

    val full = all.filter { has(it.displayName) }
    if (full.isNotEmpty()) return full

    return all.filter { team -> team.aliases.any(::has) }
        .groupBy { team -> team.aliases.first(::has).lowercase() }
        .flatMap { (_, candidates) ->
            if (candidates.size == 1) {
                candidates
            } else {
                val hinted = candidates.filter { c ->
                    LEAGUES.first { it.first == c.league }.second.any(::has)
                }
                if (hinted.size == 1) hinted else emptyList()
            }
        }
}

/**
 * Game lines for the user's teams: live, finished in the last day, or starting
 * within a week. Live games first, then soonest.
 */
suspend fun readGames(app: TomsenseApp, interests: List<NewsClient.Interest>): List<Game> {
    if (interests.isEmpty()) return emptyList()
    val all = teams(app)
    val followed = interests.flatMap { matchTeams(it.name + " " + it.queryText, all) }
        .distinctBy { it.league + it.id }
        .take(6)

    return followed.mapNotNull { team -> readGame(app, team) }
        .sortedWith(compareBy({ !it.live }, { it.startsAt }))
        .take(MAX_GAMES)
}

private suspend fun readGame(app: TomsenseApp, team: Team): Game? = runCatching {
    val res = app.httpClient.get("https://site.api.espn.com/apis/site/v2/sports/${team.league}/teams/${team.id}")
    if (!res.status.isSuccess()) return null
    val event = Json.parseToJsonElement(res.body<String>()).jsonObject["team"]?.jsonObject
        ?.get("nextEvent")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val comp = event["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val status = comp["status"]?.jsonObject?.get("type")?.jsonObject
    val state = status?.str("state")
    val detail = status?.str("shortDetail").orEmpty()
    val sides = comp["competitors"]?.jsonArray.orEmpty().map { it.jsonObject }
    val url = event["links"]?.jsonArray?.firstOrNull()?.jsonObject?.str("href")?.ifBlank { null }
    val start = runCatching { OffsetDateTime.parse(event.str("date")) }.getOrNull() ?: return null
    val local = start.atZoneSameInstant(ZoneId.systemDefault())
    val score = sides.joinToString(" – ") { "${it.teamAbbr()} ${it.score()}" }

    val text = when (state) {
        "in" -> "$score · $detail"
        "post" -> {
            if (LocalDate.now().toEpochDay() - local.toLocalDate().toEpochDay() > 1) return null
            "Final: $score"
        }
        else -> {
            if (start.toInstant().toEpochMilli() - System.currentTimeMillis() > 7 * 86_400_000L) return null
            event.str("shortName") + " · " + local.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) +
                " " + shortTime(app, local.toLocalTime())
        }
    }
    Game(text = text, url = url, team = team.displayName, live = state == "in", startsAt = start.toEpochSecond())
}.getOrNull()

private fun JsonObject.str(key: String): String =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }.orEmpty()

private fun JsonObject.teamAbbr(): String = this["team"]?.jsonObject?.str("abbreviation").orEmpty()

/** Scores arrive as a string on team endpoints and as an object on others. */
private fun JsonObject.score(): String {
    val s = this["score"] ?: return "0"
    return runCatching { s.jsonObject.str("displayValue") }.getOrNull()?.ifBlank { null }
        ?: runCatching { s.jsonPrimitive.content }.getOrDefault("0")
}
