package com.redclient.orbital.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.redclient.orbital.OrbitalGraph
import com.redclient.orbital.core.GuestManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class HomeUi(
    val guests: List<GuestManifest> = emptyList(),
    val snackbar: String? = null,
)

/**
 * Drives the Home screen. Reads the reactive guest list out of the
 * registry and exposes a single command — [launch] — that UI can call.
 */
class HomeViewModel(private val graph: OrbitalGraph) : ViewModel() {

    private val _snackbar = MutableStateFlow<String?>(null)

    val ui: StateFlow<HomeUi> = combine(graph.registry.guests, _snackbar) { guests, sb ->
        HomeUi(guests = guests, snackbar = sb)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun launch(pkg: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { graph.launcher.launch(pkg) }
            result.onErr { err ->
                Timber.e(err.cause, "HomeViewModel: launch failed — %s", err.message)
                _snackbar.value = err.message
            }
        }
    }

    fun uninstall(pkg: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.registry.remove(pkg)
                graph.paths.apkDirFor(pkg).deleteRecursively()
                graph.paths.dexCacheFor(pkg).deleteRecursively()
                graph.slots.release(pkg)
            }
            _snackbar.value = "Uninstalled"
        }
    }

    fun dismissSnackbar() {
        _snackbar.value = null
    }

    class Factory(private val graph: OrbitalGraph) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(graph) as T
        }
    }
}
