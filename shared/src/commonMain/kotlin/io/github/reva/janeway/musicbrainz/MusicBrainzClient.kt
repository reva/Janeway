package io.github.reva.janeway.musicbrainz

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * MusicBrainz web service v2 client.
 * See https://musicbrainz.org/doc/MusicBrainz_API (limit: 1 request per second, User-Agent required).
 */
class MusicBrainzClient(
    private val http: HttpClient = defaultHttpClient(),
) {
    private val rateLimit = Mutex()
    private var lastRequest: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Searches by exact title and artist phrases first, then by their individual words.
     * No track count filter: the `tracks` search field counts tracks per medium, not per release.
     */
    suspend fun searchReleases(album: String?, artist: String?): List<Release> {
        val fields = listOfNotNull(
            album?.takeIf { it.isNotBlank() }?.let { "release" to it },
            artist?.takeIf { it.isNotBlank() }?.let { "artist" to it },
        )
        if (fields.isEmpty()) return emptyList()

        val phrases = fields.joinToString(" AND ") { (field, value) -> "$field:\"${value.phraseEscaped()}\"" }
        return search(phrases).ifEmpty {
            search(fields.joinToString(" AND ") { (field, value) -> "$field:(${value.luceneEscaped()})" })
        }
    }

    /** Release summaries for up to 25 release IDs in one request. */
    suspend fun releasesByIds(ids: List<String>): List<Release> {
        if (ids.isEmpty()) return emptyList()
        return search(ids.take(25).joinToString(" OR ") { "reid:\"$it\"" })
    }

    private suspend fun search(query: String): List<Release> = throttled {
        http.get("release/") {
            parameter("query", query)
            parameter("limit", 25)
        }.body<ReleaseSearchResponse>().releases
    }

    suspend fun release(id: String): Release = throttled {
        http.get("release/$id") {
            parameter("inc", "recordings+artist-credits+release-groups+labels")
        }.body()
    }

    /** Spaces requests and retries with backoff when MusicBrainz answers 503 (rate limited or busy). */
    private suspend fun <T> throttled(block: suspend () -> T): T = rateLimit.withLock {
        var backoff = MIN_INTERVAL
        repeat(MAX_ATTEMPTS) { attempt ->
            lastRequest?.let { mark ->
                val wait = backoff - mark.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            try {
                return@withLock block()
            } catch (e: ServerResponseException) {
                if (e.response.status != HttpStatusCode.ServiceUnavailable || attempt == MAX_ATTEMPTS - 1) {
                    throw MusicBrainzException("MusicBrainz returned ${e.response.status.value}", e)
                }
                backoff *= 2
            } finally {
                lastRequest = TimeSource.Monotonic.markNow()
            }
        }
        error("unreachable")
    }

    companion object {
        private val MIN_INTERVAL = 1.1.seconds
        private const val MAX_ATTEMPTS = 4
        const val USER_AGENT = "Janeway/0.1.0 ( https://github.com/reva/Janeway )"

        fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
            }
            defaultRequest {
                url("https://musicbrainz.org/ws/2/")
                url.parameters.append("fmt", "json")
                headers.append(HttpHeaders.UserAgent, USER_AGENT)
            }
            expectSuccess = true
        }
    }
}

class MusicBrainzException(message: String, cause: Throwable) : Exception(message, cause)

private val luceneSpecial = Regex("""([+\-&|!(){}\[\]^"~*?:\\/])""")

private fun String.luceneEscaped(): String = replace(luceneSpecial, """\\$1""")

private fun String.phraseEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private val mbidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

/** Extracts a release MBID from a MusicBrainz release link or a bare ID. */
fun parseReleaseId(input: String): String? =
    input.trim().takeIf { "musicbrainz.org/release/" in it || mbidPattern.matches(it) }
        ?.let { mbidPattern.find(it)?.value?.lowercase() }
