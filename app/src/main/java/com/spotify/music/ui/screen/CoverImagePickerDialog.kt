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
        onPathSelected = onCoverSelected,
        initiallySelectedPath = initiallySelectedCover
    )
}