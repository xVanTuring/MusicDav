package tech.xvanturing.musicdav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

object UiSettings {
    private const val PREFS = "ui_prefs" // same prefs file AlbumListScreen already uses for home_view_mode
    private const val KEY_SHOW_LIST_COVERS = "show_list_covers"

    // Compose-observable; reading it in a composable recomposes on change.
    var showListCovers by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        showListCovers = prefs.getBoolean(KEY_SHOW_LIST_COVERS, true)
    }

    fun setShowListCovers(context: Context, value: Boolean) {
        showListCovers = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_LIST_COVERS, value)
        }
    }
}
