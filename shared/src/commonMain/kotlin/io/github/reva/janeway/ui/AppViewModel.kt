package io.github.reva.janeway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.reva.janeway.acoustid.AcoustIdClient
import io.github.reva.janeway.acoustid.AcoustIdException
import io.github.reva.janeway.acoustid.Identification
import io.github.reva.janeway.library.AlbumCluster
import io.github.reva.janeway.library.AudioLibrary
import io.github.reva.janeway.library.LibraryFilter
import io.github.reva.janeway.library.TagIssue
import io.github.reva.janeway.library.clusterByAlbum
import io.github.reva.janeway.library.issueCounts
import io.github.reva.janeway.model.AudioFile
import io.github.reva.janeway.model.FolderRef
import io.github.reva.janeway.musicbrainz.CoverArtClient
import io.github.reva.janeway.musicbrainz.MusicBrainzClient
import io.github.reva.janeway.musicbrainz.Release
import io.github.reva.janeway.musicbrainz.TrackMatch
import io.github.reva.janeway.musicbrainz.countReleaseVotes
import io.github.reva.janeway.musicbrainz.matchFilesToRelease
import io.github.reva.janeway.musicbrainz.parseReleaseId
import io.github.reva.janeway.musicbrainz.rankByAudioVotes
import io.github.reva.janeway.musicbrainz.rankReleases
import io.github.reva.janeway.musicbrainz.tagsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val folder: FolderRef? = null,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val scanTotal: Int = 0,
    val files: List<AudioFile> = emptyList(),
    val clusters: List<AlbumCluster> = emptyList(),
    /** Issue counts per album key, computed together with [clusters]. */
    val issuesByAlbum: Map<String, Map<TagIssue, Int>> = emptyMap(),
    val filter: LibraryFilter = LibraryFilter.All,
    val screen: Screen = Screen.Library,
    val message: String? = null,
)

sealed interface Screen {
    data object Library : Screen
    data class Cluster(
        val key: String,
        val lookup: Lookup = Lookup.Idle,
        /** MusicBrainz recording IDs AcoustID found per file ID. */
        val recordingsByFile: Map<String, Set<String>> = emptyMap(),
    ) : Screen
    data class Match(
        /** The album screen to return to, with its search results and fingerprints. */
        val from: Cluster,
        val release: Release,
        val matches: List<TrackMatch>,
        val embedCover: Boolean = release.coverArt?.front == true,
        val saving: Boolean = false,
    ) : Screen
}

sealed interface Lookup {
    data object Idle : Lookup
    data object Searching : Lookup
    data class Identifying(val done: Int, val total: Int) : Lookup
    data class Results(
        val releases: List<Release>,
        val loadingReleaseId: String? = null,
        /** Files AcoustID placed on each release, when found by audio. */
        val audioVotes: Map<String, Int> = emptyMap(),
        val fingerprintedFiles: Int = 0,
    ) : Lookup
    data class Failed(val message: String) : Lookup
}

class AppViewModel(
    private val library: AudioLibrary,
    private val musicBrainz: MusicBrainzClient,
    private val coverArt: CoverArtClient,
    private val acoustId: AcoustIdClient,
    /** Built into the app at build time; null when the build had none. */
    private val acoustIdApiKey: String?,
) : ViewModel() {
    private var scanJob: Job? = null

    /** The running search, fingerprinting or release load of the open album. */
    private var lookupJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        library.lastFolder()?.let(::scan)
    }

    fun scan(folder: FolderRef) {
        scanJob?.cancel()
        _state.update { it.copy(folder = folder, scanning = true, scannedCount = 0, scanTotal = 0, screen = Screen.Library) }
        scanJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            try {
                val files = library.scan(folder) { done, total ->
                    // Reads still finishing after a newer scan started must not move its progress.
                    if (job?.isActive == true) _state.update { it.copy(scannedCount = done, scanTotal = total) }
                }
                val organized = organize(files)
                _state.update { it.withLibrary(files, organized).copy(scanning = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(scanning = false, message = "Scan failed: ${e.message}") }
            }
        }
    }

    fun openCluster(cluster: AlbumCluster) {
        lookupJob?.cancel()
        _state.update { it.copy(screen = Screen.Cluster(cluster.key)) }
    }

    /** Searches by album and artist, or opens the release directly when [album] is a MusicBrainz release link or ID. */
    fun lookup(album: String, artist: String) {
        val screen = _state.value.screen as? Screen.Cluster ?: return
        val cluster = clusterFor(screen.key) ?: return
        startLookup(screen.copy(lookup = Lookup.Searching)) {
            val releaseId = parseReleaseId(album)
            if (releaseId != null) {
                openRelease(cluster, releaseId, restore = Lookup.Idle)
                return@startLookup
            }
            val lookup = try {
                Lookup.Results(rankReleases(musicBrainz.searchReleases(album, artist), cluster.files.size))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Lookup.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            updateCluster(cluster.key) { it.copy(lookup = lookup) }
        }
    }

    /** Fingerprints every file of the open album and looks the fingerprints up on AcoustID. */
    fun identify() {
        val screen = _state.value.screen as? Screen.Cluster ?: return
        val cluster = clusterFor(screen.key) ?: return
        val apiKey = acoustIdApiKey
        if (apiKey == null) {
            updateScreen(screen.copy(lookup = Lookup.Failed("This build has no AcoustID API key, see the README")))
            return
        }
        val total = cluster.files.size
        startLookup(screen.copy(lookup = Lookup.Identifying(0, total))) {
            val lookup = try {
                val identified = mutableMapOf<String, Identification>()
                cluster.files.forEachIndexed { index, file ->
                    library.fingerprint(file)?.let { identified[file.id] = acoustId.lookup(apiKey, it) }
                    updateCluster(cluster.key) { it.copy(lookup = Lookup.Identifying(index + 1, total)) }
                }
                if (identified.isEmpty()) {
                    Lookup.Failed("None of the files could be decoded for fingerprinting")
                } else {
                    val votes = countReleaseVotes(identified.values.map { it.releaseIds })
                    val topIds = votes.entries.sortedByDescending { it.value }.take(25).map { it.key }
                    val releases = rankByAudioVotes(musicBrainz.releasesByIds(topIds), votes, total)
                    updateCluster(cluster.key) { it.copy(recordingsByFile = identified.mapValues { (_, id) -> id.recordingIds }) }
                    Lookup.Results(releases, audioVotes = votes, fingerprintedFiles = identified.size)
                }
            } catch (e: AcoustIdException) {
                Lookup.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Lookup.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            updateCluster(cluster.key) { it.copy(lookup = lookup) }
        }
    }

    fun chooseRelease(release: Release) {
        val screen = _state.value.screen as? Screen.Cluster ?: return
        val results = screen.lookup as? Lookup.Results ?: return
        val cluster = clusterFor(screen.key) ?: return
        startLookup(screen.copy(lookup = results.copy(loadingReleaseId = release.id))) {
            openRelease(cluster, release.id, restore = results)
        }
    }

    private suspend fun openRelease(cluster: AlbumCluster, releaseId: String, restore: Lookup) {
        try {
            val full = musicBrainz.release(releaseId)
            updateCluster(cluster.key) {
                Screen.Match(
                    from = it.copy(lookup = restore),
                    release = full,
                    matches = matchFilesToRelease(cluster.files, full, it.recordingsByFile),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateCluster(cluster.key) { it.copy(lookup = restore) }
            _state.update { it.copy(message = "Could not load release: ${e.message}") }
        }
    }

    fun setEmbedCover(embed: Boolean) {
        updateScreenIf<Screen.Match> { if (it.saving) it else it.copy(embedCover = embed) }
    }

    /** Writes tags and cover. Back is ignored until it finishes, so files are never left half-written. */
    fun save() {
        val screen = _state.value.screen as? Screen.Match ?: return
        if (screen.saving) return
        updateScreen(screen.copy(saving = true))
        viewModelScope.launch {
            val cover = if (screen.embedCover) attempt { coverArt.frontCover(screen.release.id) } else null
            val updated = mutableMapOf<String, AudioFile>()
            var failed = 0
            var coverFailed = 0
            for (match in screen.matches) {
                val track = match.track ?: continue
                if (attempt { library.writeTags(match.file, tagsFor(screen.release, track)) } != true) {
                    failed++
                    continue
                }
                if (cover != null && attempt { library.writeFrontCover(match.file, cover, "image/jpeg") } != true) {
                    coverFailed++
                }
                updated[match.file.id] = attempt { library.reload(match.file) } ?: match.file
            }

            val files = _state.value.files.map { updated[it.id] ?: it }
            val organized = organize(files)
            _state.update { state ->
                state.withLibrary(files, organized).copy(
                    screen = Screen.Library,
                    message = buildString {
                        append("Saved tags for ${updated.size} file(s)")
                        if (cover != null && coverFailed < updated.size) append(" with cover art")
                        if (screen.embedCover && cover == null) append(", cover art could not be downloaded")
                        if (coverFailed > 0) append(", cover failed for $coverFailed")
                        if (failed > 0) append(", $failed failed")
                    },
                )
            }
        }
    }

    fun back() {
        val screen = _state.value.screen
        if (screen is Screen.Match && screen.saving) return
        lookupJob?.cancel()
        _state.update { state ->
            val previous = when (val current = state.screen) {
                is Screen.Match -> current.from.takeIf { clusterFor(it.key) != null } ?: Screen.Library
                else -> Screen.Library
            }
            state.copy(screen = previous)
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clusterFor(key: String): AlbumCluster? = _state.value.clusters.firstOrNull { it.key == key }

    private fun startLookup(screen: Screen.Cluster, block: suspend () -> Unit) {
        lookupJob?.cancel()
        updateScreen(screen)
        lookupJob = viewModelScope.launch { block() }
    }

    private fun updateScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    /** Applies the change only while that album is still the open screen, so late results never land elsewhere. */
    private fun updateCluster(key: String, change: (Screen.Cluster) -> Screen) {
        _state.update { state ->
            val current = state.screen as? Screen.Cluster
            if (current?.key == key) state.copy(screen = change(current)) else state
        }
    }

    /** Applies the change only if the user is still on a screen of that type. */
    private inline fun <reified S : Screen> updateScreenIf(crossinline change: (S) -> Screen) {
        _state.update { state ->
            val current = state.screen as? S ?: return@update state
            state.copy(screen = change(current))
        }
    }

    private class Organized(val clusters: List<AlbumCluster>, val issues: Map<String, Map<TagIssue, Int>>)

    private suspend fun organize(files: List<AudioFile>): Organized = withContext(Dispatchers.Default) {
        val clusters = clusterByAlbum(files)
        Organized(clusters, clusters.associate { it.key to it.issueCounts() })
    }

    private fun UiState.withLibrary(files: List<AudioFile>, organized: Organized) =
        copy(files = files, clusters = organized.clusters, issuesByAlbum = organized.issues)
}

/** Like runCatching, but lets coroutine cancellation through. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
