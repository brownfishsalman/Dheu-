package com.salmansaadiq.dheu.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.salmansaadiq.dheu.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "settings")

/** Light / dark / system, chosen on this device (like the website's theme picker). */
object ThemePrefs {
    private val KEY = stringPreferencesKey("theme")

    fun flow(context: Context): Flow<ThemeMode> =
        context.settings.data.map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[KEY] } ?: ThemeMode.System }

    suspend fun set(context: Context, mode: ThemeMode) {
        context.settings.edit { it[KEY] = mode.name }
    }
}
