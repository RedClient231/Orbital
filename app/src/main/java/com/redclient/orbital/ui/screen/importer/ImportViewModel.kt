package com.redclient.orbital.ui.screen.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.redclient.orbital.OrbitalGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class ImportUi(
    val isImporting: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
)

class ImportViewModel(private val graph: OrbitalGraph) : ViewModel() {

    private val _ui = MutableStateFlow(ImportUi())
    val ui = _ui.asStateFlow()

    fun import(uri: Uri) {
        if (_ui.value.isImporting) return
        _ui.value = ImportUi(isImporting = true)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { graph.importer.importFromUri(uri) }
            result
                .onOk { imported ->
                    Timber.i("Import OK: %s", imported.manifest.packageName)
                    _ui.value = ImportUi(
                        isImporting = false,
                        message = "Imported ${imported.manifest.appName}",
                        done = true,
                    )
                }
                .onErr { err ->
                    Timber.e(err.cause, "Import failed: %s", err.message)
                    _ui.value = ImportUi(
                        isImporting = false,
                        message = err.message,
                        done = false,
                    )
                }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    class Factory(private val graph: OrbitalGraph) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ImportViewModel(graph) as T
        }
    }
}
