package io.github.reva.janeway.acoustid

import io.github.reva.janeway.musicbrainz.MusicBrainzClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

data class AudioFingerprint(val fingerprint: String, val durationSeconds: Int)

/** MusicBrainz recordings and releases AcoustID associates with one file. */
data class Identification(val recordingIds: Set<String>, val releaseIds: Set<String>)

class AcoustIdException(message: String) : Exception(message)

/** AcoustID lookup API (https://acoustid.org/webservice), limited to 3 requests per second. */
class AcoustIdClient(
    private val http: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) {
    private val rateLimit = Mutex()
    private var lastRequest: TimeSource.Monotonic.ValueTimeMark? = null

    suspend fun lookup(apiKey: String, fingerprint: AudioFingerprint): Identification {
        val response = rateLimit.withLock {
            lastRequest?.let { mark ->
                val wait = MIN_INTERVAL - mark.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            try {
                http.submitForm(
                    url = "https://api.acoustid.org/v2/lookup",
                    formParameters = parameters {
                        append("client", apiKey)
                        append("meta", "recordings releaseids")
                        append("duration", fingerprint.durationSeconds.toString())
                        append("fingerprint", fingerprint.fingerprint)
                        append("format", "json")
                    },
                ) {
                    header(HttpHeaders.UserAgent, MusicBrainzClient.USER_AGENT)
                }.body<LookupResponse>()
            } finally {
                lastRequest = TimeSource.Monotonic.markNow()
            }
        }

        if (response.status != "ok") {
            val error = response.error
            throw AcoustIdException(
                if (error?.code == INVALID_API_KEY) {
                    "AcoustID rejected the API key built into this app"
                } else {
                    "AcoustID: ${error?.message ?: response.status}"
                },
            )
        }
        val recordings = response.results.filter { it.score >= MIN_SCORE }.flatMap { it.recordings }
        return Identification(
            recordingIds = recordings.mapTo(mutableSetOf()) { it.id },
            releaseIds = recordings.flatMapTo(mutableSetOf()) { recording -> recording.releases.map { it.id } },
        )
    }

    private companion object {
        val MIN_INTERVAL = 350.milliseconds
        const val MIN_SCORE = 0.5
        const val INVALID_API_KEY = 4
    }
}

@Serializable
private data class LookupResponse(
    val status: String,
    val results: List<LookupResult> = emptyList(),
    val error: LookupError? = null,
)

@Serializable
private data class LookupResult(val id: String, val score: Double = 0.0, val recordings: List<LookupRecording> = emptyList())

@Serializable
private data class LookupRecording(val id: String, val releases: List<IdOnly> = emptyList())

@Serializable
private data class IdOnly(val id: String)

@Serializable
private data class LookupError(val code: Int? = null, val message: String? = null)
