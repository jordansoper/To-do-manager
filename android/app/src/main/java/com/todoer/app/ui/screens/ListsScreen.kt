package com.todoer.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoer.app.data.api.TodoList
import com.todoer.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    lists: List<TodoList>,
    isLoading: Boolean,
    onListClick: (TodoList) -> Unit,
    onCreateList: (String) -> Unit,
    onDeleteList: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteListTarget by remember { mutableStateOf<TodoList?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("To-Doer", color = Purple) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = Purple
            ) {
                Icon(Icons.Default.Add, contentDescription = "New list")
            }
        }
    ) { padding ->
        if (isLoading && lists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Purple)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onListClick(list) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.List,
                                contentDescription = null,
                                tint = Purple,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = list.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (list.id != 1) {
                                IconButton(
                                    onClick = { deleteListTarget = list },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete list",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Create list dialog
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New List") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("List name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateList(name)
                            showCreateDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete list confirmation
    deleteListTarget?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteListTarget = null },
            title = { Text("Delete List") },
            text = { Text("Delete \"${list.name}\" and all its tasks?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteList(list.id)
                        deleteListTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteListTarget = null }) { Text("Cancel") }
            }
        )
    }
}
