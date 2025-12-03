package com.spotify.music.ui.screen

import androidx.compose.runtime.Composable
import com.spotify.music.data.WebDavConfig

@Composable
fun FolderPickerDialog(
    isVisible: Boolean,
    webDavConfig: WebDavConfig,
    initialPath: String,
    onDismiss: () -> Unit,
    onFolderSelected: (folderPath: String) -> Unit,
    onClearCoverSelection: (() -> Unit)? = null,
    hasCoverSelection: Boolean = false
) {
    val config = FilePickerConfig(
        title = "选择文件夹",
        mode = FilePickerMode.DIRECTORY_ONLY,
        showClearSelectionButton = hasCoverSelection,
        showFileIcons = true,
        showFilesInDirectoryMode = true
    )

    FilePickerDialog(
        isVisible = isVisible,
        webDavConfig = webDavConfig,
        initialPath = initialPath,
        config = config,
        onDismiss = onDismiss,
        onPathSelected = { selectedPath ->
            selectedPath?.let { onFolderSelected(it) }
        },
        customButtons = if (onClearCoverSelection != null && hasCoverSelection) {
            {
                // 清除封面选择按钮已经通过 config.showClearSelectionButton 处理
                // 这里可以添加额外的自定义按钮逻辑
            }
        } else null
    )
}