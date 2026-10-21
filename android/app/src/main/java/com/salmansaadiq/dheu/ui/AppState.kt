package com.salmansaadiq.dheu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.salmansaadiq.dheu.data.Activity
import com.salmansaadiq.dheu.data.Badges
import com.salmansaadiq.dheu.data.Profile
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Signed-in member's profile and badge counts, shared by every screen. */
class AppState(val userId: String) : ViewModel() {
    private val _me = MutableStateFlow<Profile?>(null)
    val me = _me.asStateFlow()

    private val _meError = MutableStateFlow<String?>(null)
    val meError = _meError.asStateFlow()

    private val _badges = MutableStateFlow(Badges())
    val badges = _badges.asStateFlow()

    private var pending: Job? = null

    init {
        refreshMe()
        refreshBadges()
        viewModelScope.launch {
            Activity.badgeEvents(userId).catch { /* realtime is a nicety; badges still refresh on navigation */ }
                .collect { refreshBadgesSoon() }
        }
    }

    fun refreshMe() {
        viewModelScope.launch {
            try {
                _me.value = Profiles.byId(userId)
                _meError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _meError.value = e.friendly("Couldn't load your profile.")
            }
        }
    }

    fun refreshBadges() {
        viewModelScope.launch { runCatching { Activity.badges() }.onSuccess { _badges.value = it } }
    }

    /** Realtime events come in bursts; collapse each burst into one refresh. */
    fun refreshBadgesSoon() {
        if (pending?.isActive == true) return
        pending = viewModelScope.launch {
            delay(300)
            refreshBadges()
        }
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState not provided") }

// ---- a small "loading / failed / ready" holder for screens -------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

class Loader<T>(private val fetch: suspend () -> T) : ViewModel() {
    var state by mutableStateOf<Load<T>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        reload()
    }

    /** Reload; with [silent] the current data stays on screen meanwhile (pull to refresh). */
    fun reload(silent: Boolean = false) {
        viewModelScope.launch {
            if (silent && state is Load.Ready) refreshing = true else state = Load.Loading
            state = try {
                Load.Ready(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (silent && state is Load.Ready) state else Load.Failed(e.friendly())
            }
            refreshing = false
        }
    }

    fun update(transform: (T) -> T) {
        (state as? Load.Ready)?.let { state = Load.Ready(transform(it.value)) }
    }
}

@Composable
inline fun <reified T> rememberLoader(key: String, noinline fetch: suspend () -> T): Loader<T> =
    viewModel(key = key) { Loader(fetch) }
