package io.github.reva.janeway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.OutlinedButton
import coil3.compose.AsyncImage
import io.github.reva.janeway.acoustid.AcoustIdClient
import io.github.reva.janeway.musicbrainz.CoverArtClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.reva.janeway.library.AlbumCluster
import io.github.reva.janeway.library.AudioLibrary
import io.github.reva.janeway.library.LibraryFilter
import io.github.reva.janeway.library.TagIssue
import io.github.reva.janeway.library.issueCounts
import io.github.reva.janeway.library.issues
import io.github.reva.janeway.library.matches
import io.github.reva.janeway.musicbrainz.MusicBrainzClient
import io.github.reva.janeway.musicbrainz.Release
import io.github.reva.janeway.musicbrainz.TrackMatch

@Composable
fun App(library: AudioLibrary, acoustIdApiKey: String?) {
    val viewModel = viewModel {
        AppViewModel(library, MusicBrainzClient(), CoverArtClient(), AcoustIdClient(), acoustIdApiKey)
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        AppContent(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val pickFolder = rememberFolderPicker(viewModel::scan)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    PlatformBackHandler(enabled = state.screen != Screen.Library, onBack = viewModel::back)

    val title = when (val screen = state.screen) {
        Screen.Library -> state.folder?.displayName ?: "Janeway"
        is Screen.Cluster -> viewModel.clusterFor(screen.key)?.album ?: "Album"
        is Screen.Match -> screen.release.title
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (state.screen != Screen.Library) TextButton(onClick = viewModel::back) { Text("Back") }
                },
                actions = {
                    if (state.screen == Screen.Library) TextButton(onClick = pickFolder) { Text("Folder") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = state.screen) {
                Screen.Library -> LibraryScreen(state, pickFolder, viewModel::openCluster, viewModel::setFilter)
                is Screen.Cluster -> viewModel.clusterFor(screen.key)
                    ?.let {
                        ClusterScreen(
                            cluster = it,
                            lookup = screen.lookup,
                            onLookup = viewModel::lookup,
                            onIdentify = viewModel::identify,
                            onChoose = viewModel::chooseRelease,
                        )
                    }
                is Screen.Match -> MatchScreen(screen, viewModel::setEmbedCover, viewModel::save)
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: UiState,
    pickFolder: () -> Unit,
    open: (AlbumCluster) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    when {
        state.scanning -> Centered {
            CircularProgressIndicator()
            val progress = if (state.scanTotal == 0) {
                "Looking for audio files…"
            } else {
                "Reading tags… ${state.scannedCount} of ${state.scanTotal}"
            }
            Text(progress, Modifier.padding(top = 16.dp))
        }
        state.folder == null -> Centered {
            Text("Choose the folder that contains your music.")
            Button(onClick = pickFolder, modifier = Modifier.padding(top = 16.dp)) { Text("Choose folder") }
        }
        state.clusters.isEmpty() -> Centered { Text("No audio files found in this folder.") }
        else -> {
            val issuesByAlbum = remember(state.clusters) { state.clusters.associate { it.key to it.issueCounts() } }
            val visible = state.clusters.filter { state.filter.matches(issuesByAlbum.getValue(it.key)) }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "${state.files.size} files in ${state.clusters.size} albums",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
                item { FilterChips(state.filter, issuesByAlbum.values, onFilter) }
                if (visible.isEmpty()) {
                    item { Text("No albums match this filter.", Modifier.padding(16.dp)) }
                }
                items(visible, key = { it.key }) { cluster ->
                    val issues = issuesByAlbum.getValue(cluster.key)
                    ListItem(
                        headlineContent = { Text(cluster.album ?: "Unknown album") },
                        supportingContent = {
                            Column {
                                Text(cluster.artist ?: "Unknown artist")
                                if (issues.isNotEmpty()) IssueText(issueSummary(issues, cluster.files.size))
                            }
                        },
                        trailingContent = { Text("${cluster.files.size}") },
                        modifier = Modifier.clickable { open(cluster) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One chip per issue that occurs, each with the number of albums it affects. */
@Composable
private fun FilterChips(
    selected: LibraryFilter,
    issuesByAlbum: Collection<Map<TagIssue, Int>>,
    onFilter: (LibraryFilter) -> Unit,
) {
    val chips = buildList {
        add(LibraryFilter.All to "All")
        add(LibraryFilter.NeedsAttention to "Needs attention (${issuesByAlbum.count { it.isNotEmpty() }})")
        TagIssue.entries.forEach { issue ->
            val albums = issuesByAlbum.count { issue in it }
            val filter = LibraryFilter.Has(issue)
            if (albums > 0 || selected == filter) add(filter to "${issue.label.replaceFirstChar { it.uppercase() }} ($albums)")
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.second.substringBefore(" (") }) { (filter, label) ->
            FilterChip(selected = selected == filter, onClick = { onFilter(filter) }, label = { Text(label) })
        }
    }
}

/** "no title (3) · no cover": an issue without a count affects every file of the album. */
private fun issueSummary(issues: Map<TagIssue, Int>, fileCount: Int): String =
    issues.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { (issue, count) ->
        if (count == fileCount) issue.label else "${issue.label} ($count)"
    }

@Composable
private fun IssueText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ClusterScreen(
    cluster: AlbumCluster,
    lookup: Lookup,
    onLookup: (album: String, artist: String) -> Unit,
    onIdentify: () -> Unit,
    onChoose: (Release) -> Unit,
) {
    val busy = lookup == Lookup.Searching || lookup is Lookup.Identifying
    var album by rememberSaveable(cluster.key) { mutableStateOf(cluster.album.orEmpty()) }
    var artist by rememberSaveable(cluster.key) { mutableStateOf(cluster.artist.orEmpty()) }
    val search = { onLookup(album.trim(), artist.trim()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionHeader("MusicBrainz")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album or MusicBrainz release link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = search, enabled = !busy && (album.isNotBlank() || artist.isNotBlank())) {
                        Text("Search")
                    }
                    OutlinedButton(onClick = onIdentify, enabled = !busy) {
                        Text("Identify by audio")
                    }
                }
                when (lookup) {
                    Lookup.Idle -> Unit
                    Lookup.Searching -> Progress("Searching…")
                    is Lookup.Identifying -> Progress("Fingerprinting ${lookup.done} of ${lookup.total} files…")
                    is Lookup.Failed -> Text(lookup.message, color = MaterialTheme.colorScheme.error)
                    is Lookup.Results -> if (lookup.releases.isEmpty()) Text("No matching releases found.")
                }
            }
        }
        if (lookup is Lookup.Results) {
            items(lookup.releases, key = { it.id }) { release ->
                ListItem(
                    leadingContent = { CoverImage(release.id, size = 250, modifier = Modifier.size(56.dp)) },
                    headlineContent = { Text("${release.title} · ${release.artistName}") },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                release.date,
                                release.country,
                                release.formatSummary.takeIf { it.isNotEmpty() },
                                "${release.totalTracks} tracks",
                                if (lookup.audioVotes.isNotEmpty()) {
                                    "audio match ${lookup.audioVotes[release.id] ?: 0}/${lookup.fingerprintedFiles}"
                                } else {
                                    release.score?.let { "score $it" }
                                },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        if (lookup.loadingReleaseId == release.id) CircularProgressIndicator(Modifier.size(20.dp))
                    },
                    modifier = Modifier.clickable(enabled = lookup.loadingReleaseId == null) { onChoose(release) },
                )
                HorizontalDivider()
            }
        }
        item { SectionHeader("Files (${cluster.files.size})") }
        items(cluster.files, key = { it.id }) { file ->
            val position = file.trackNumber?.let { track -> file.discNumber?.let { "#$it-$track" } ?: "#$track" }
            ListItem(
                headlineContent = { Text(file.title ?: file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(position, file.artist, file.durationMs?.let(::formatLength)).joinToString(" · "))
                        val issues = file.issues()
                        if (issues.isNotEmpty()) IssueText(issues.sortedBy { it.ordinal }.joinToString(" · ") { it.label })
                    }
                },
                overlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun MatchScreen(screen: Screen.Match, onEmbedCoverChange: (Boolean) -> Unit, onSave: () -> Unit) {
    val matched = screen.matches.count { it.track != null }
    val hasCover = screen.release.coverArt?.front == true
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(screen.release.id, size = 500, modifier = Modifier.size(120.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(screen.release.artistName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(screen.release.date, screen.release.country).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("$matched of ${screen.matches.size} files matched", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (hasCover) "Embed cover art" else "No cover art on MusicBrainz",
                        Modifier.weight(1f),
                    )
                    Switch(
                        checked = screen.embedCover,
                        onCheckedChange = onEmbedCoverChange,
                        enabled = hasCover && !screen.saving,
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            items(screen.matches, key = { it.file.id }) { match -> MatchRow(match) }
        }
        Button(
            onClick = onSave,
            enabled = matched > 0 && !screen.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(if (screen.saving) "Saving…" else "Save tags to $matched files")
        }
    }
}

@Composable
private fun MatchRow(match: TrackMatch) {
    val target = match.track
    ListItem(
        overlineContent = { Text(match.file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = {
            if (target == null) {
                Text("No matching track", color = MaterialTheme.colorScheme.error)
            } else {
                val disc = if (target.medium.position > 1) "${target.medium.position}-" else ""
                Text("$disc${target.track.position}. ${target.track.title}")
            }
        },
        supportingContent = {
            Text("was: ${match.file.title ?: "(no title)"}" + (match.file.trackNumber?.let { " (#$it)" } ?: ""))
        },
        trailingContent = { if (target != null) Text("${(match.score * 100).toInt()}%") },
    )
    HorizontalDivider()
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(text, Modifier.padding(start = 12.dp))
    }
}

/** Front cover from the Cover Art Archive; stays an empty tile if the release has none. */
@Composable
private fun CoverImage(releaseId: String, size: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    AsyncImage(
        model = CoverArtClient.frontCoverUrl(releaseId, size),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant, shape),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

private fun formatLength(ms: Int): String {
    val seconds = ms / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
