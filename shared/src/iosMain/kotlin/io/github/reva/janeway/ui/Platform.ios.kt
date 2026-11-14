package io.github.reva.janeway.ui

import androidx.compose.runtime.Composable
import io.github.reva.janeway.model.FolderRef

// TODO(ios): present UIDocumentPickerViewController for folders and keep a security-scoped bookmark.
@Composable
actual fun rememberFolderPicker(onPicked: (FolderRef) -> Unit): () -> Unit = {}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
