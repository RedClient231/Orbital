package com.redclient.orbital.ui.screen.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redclient.orbital.orbital

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val graph = ctx.orbital()
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory(graph))
    val ui by vm.ui.collectAsStateWithLifecycle()

    // Android's file picker — limited to APK/XAPK MIME types. We can't be
    // strict here because many file managers advertise ZIP/octet-stream for
    // these files, so we accept any and let the parser report malformed ones.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(vm::import)
    }

    LaunchedEffect(ui.done) {
        if (ui.done) onDone()
    }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Body(pad = pad, busy = ui.isImporting, onPick = { picker.launch("*/*") })
    }
}

@Composable
private fun Body(pad: PaddingValues, busy: Boolean, onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Pick an APK or XAPK from your device",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Orbital will copy it into its private storage and register it in your guest list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(32.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Importing…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = onPick) {
                Text("Choose file")
            }
        }
    }
}
