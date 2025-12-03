package com.spotify.music.ui.screen

import androidx.compose.runtime.Composable
import com.spotify.music.data.WebDavConfig

@Composable
fun CoverImagePickerDialog(
    isVisible: Boolean,
    webDavConfig: WebDavConfig,
    initialPath: String,
    onDismiss: () -> Unit,
    onCoverSelected: (coverImagePath: String?) -> Unit,
    initiallySelectedCover: String? = null
) {
    val config = FilePickerConfig(
        title = "选择封面图片",
        subtitle = "点击图片文件可选择作为封面",
        mode = FilePickerMode.FILE_ONLY,
        allowedFileExtensions = setOf("jpg", "jpeg", "png", "webp"),
        showClearSelectionButton = false,
        showFileIcons = true
    )

    FilePickerDialog(
        isVisible = isVisible,
        webDavConfig = webDavConfig,
        initialPath = initialPath,
        config = config,
        onDismiss = onDismiss,
        onPathSelected = { selectedPath ->
            // Convert file path to full HTTP URL like directory selection does
            selectedPath?.let { path ->
                if (path.startsWith("http")) {
                    onCoverSelected(path)
                } else {
                    // Build full HTTP URL from file path
                    val webDavClient = com.spotify.music.webdav.WebDavClient()
                    val fullUrl = webDavClient.buildFullUrl(webDavConfig.url, path)
                    onCoverSelected(fullUrl)
                }
            } ?: onCoverSelected(null)
        },
        initiallySelectedPath = initiallySelectedCover
    )
}