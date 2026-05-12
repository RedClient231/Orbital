package com.redclient.orbital.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redclient.orbital.core.GuestManifest
import com.redclient.orbital.orbital

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onImportClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val graph = ctx.orbital()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(graph))
    val ui by vm.ui.collectAsStateWithLifecycle()

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let {
            snackHost.showSnackbar(it)
            vm.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Orbital", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onImportClick) {
                Icon(Icons.Filled.Add, contentDescription = "Import APK")
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
    ) { pad ->
        if (ui.guests.isEmpty()) EmptyState(pad) else GuestList(ui.guests, vm, pad)
    }
}

@Composable
private fun EmptyState(pad: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No guests yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap + to import an APK or XAPK",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun GuestList(
    guests: List<GuestManifest>,
    vm: HomeViewModel,
    pad: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(guests, key = { it.packageName }) { g ->
            GuestRow(
                g = g,
                onLaunch = { vm.launch(g.packageName) },
                onUninstall = { vm.uninstall(g.packageName) },
            )
        }
    }
}

@Composable
private fun GuestRow(
    g: GuestManifest,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(g.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    g.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "v${g.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onUninstall) {
                Icon(Icons.Filled.Delete, contentDescription = "Uninstall")
            }
            IconButton(onClick = onLaunch) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Launch",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
