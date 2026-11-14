package io.github.reva.janeway.ui

import androidx.compose.runtime.Composable
import io.github.reva.janeway.model.FolderRef

/** Returns a function that opens the platform folder picker. */
@Composable
expect fun rememberFolderPicker(onPicked: (FolderRef) -> Unit): () -> Unit

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
