package com.todoer.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoer.app.data.api.TodoItem
import com.todoer.app.ui.components.TodoItemCard
import com.todoer.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(
    listName: String,
    todos: List<TodoItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onToggle: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAddTodo: (String, String?, String?) -> Unit,
    onAddSubtask: (Int, String) -> Unit,
    onRefresh: () -> Unit
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newDueDate by remember { mutableStateOf("") }
    var showCompleted by remember { mutableStateOf(false) }

    val activeTodos = todos.filter { !it.completed }
    val completedTodos = todos.filter { it.completed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listName, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddForm = !showAddForm },
                containerColor = Purple
            ) {
                Icon(
                    if (showAddForm) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = "Add task"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Add task form
            AnimatedVisibility(visible = showAddForm) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            placeholder = { Text("Task title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            placeholder = { Text("Description (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newDueDate,
                            onValueChange = { newDueDate = it },
                            placeholder = { Text("Due date: YYYY-MM-DD (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (newTitle.isNotBlank()) {
                                    onAddTodo(
                                        newTitle,
                                        newDescription.ifBlank { null },
                                        newDueDate.ifBlank { null }
                                    )
                                    newTitle = ""
                                    newDescription = ""
                                    newDueDate = ""
                                    showAddForm = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Task")
                        }
                    }
                }
            }

            if (isLoading && todos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Purple)
                }
            } else if (todos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No tasks yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Text(
                            "Tap + to add your first task",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Active tasks
                    items(activeTodos, key = { it.id }) { todo ->
                        TodoItemCard(
                            todo = todo,
                            onToggle = onToggle,
                            onDelete = onDelete,
                            onAddSubtask = onAddSubtask
                        )
                    }

                    // Completed section
                    if (completedTodos.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.outline
                                )
                                TextButton(onClick = { showCompleted = !showCompleted }) {
                                    Text(
                                        "Completed (${completedTodos.size})",
                                        color = TextSecondary
                                    )
                                    Icon(
                                        if (showCompleted) Icons.Default.ExpandLess
                                        else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        if (showCompleted) {
                            items(completedTodos, key = { it.id }) { todo ->
                                TodoItemCard(
                                    todo = todo,
                                    onToggle = onToggle,
                                    onDelete = onDelete,
                                    onAddSubtask = onAddSubtask
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}
