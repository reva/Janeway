package io.github.reva.janeway.musicbrainz

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/** Cover Art Archive (https://coverartarchive.org). Thumbnails are always JPEG. */
class CoverArtClient(
    private val http: HttpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 60_000 } },
) {
    /** Returns the front cover as JPEG, or null if the release has none. */
    suspend fun frontCover(releaseId: String, size: Int = EMBED_SIZE): ByteArray? {
        val response = http.get(frontCoverUrl(releaseId, size)) {
            header(HttpHeaders.UserAgent, MusicBrainzClient.USER_AGENT)
        }
        return if (response.status.isSuccess()) response.readRawBytes() else null
    }

    companion object {
        /** Largest thumbnail size; full-size originals can be tens of megabytes. */
        const val EMBED_SIZE = 1200

        /** Allowed sizes are 250, 500 and 1200. */
        fun frontCoverUrl(releaseId: String, size: Int): String =
            "https://coverartarchive.org/release/$releaseId/front-$size"
    }
}
