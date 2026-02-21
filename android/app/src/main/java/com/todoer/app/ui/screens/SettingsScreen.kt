package com.todoer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoer.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    notificationsEnabled: Boolean,
    onBack: () -> Unit,
    onChangeServer: (String) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    var showChangeUrlDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Server section
            Text(
                "Server",
                style = MaterialTheme.typography.labelLarge,
                color = Purple
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Connected to",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showChangeUrlDialog = true }) {
                            Text("Change")
                        }
                        OutlinedButton(
                            onClick = { showDisconnectConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notifications section
            Text(
                "Notifications",
                style = MaterialTheme.typography.labelLarge,
                color = Purple
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Task reminders",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            "Get notified about tasks due today and overdue tasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        colors = SwitchDefaults.colors(checkedTrackColor = Purple)
                    )
                }
            }
        }
    }

    // Change URL dialog
    if (showChangeUrlDialog) {
        var newUrl by remember { mutableStateOf(serverUrl) }
        AlertDialog(
            onDismissRequest = { showChangeUrlDialog = false },
            title = { Text("Change Server") },
            text = {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newUrl.isNotBlank()) {
                        onChangeServer(newUrl)
                        showChangeUrlDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showChangeUrlDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Disconnect confirmation
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect") },
            text = { Text("Disconnect from this server? You can reconnect later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDisconnect()
                        showDisconnectConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
