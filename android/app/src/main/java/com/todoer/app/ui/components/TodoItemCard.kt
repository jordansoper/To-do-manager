package com.todoer.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todoer.app.data.api.TodoItem
import com.todoer.app.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TodoItemCard(
    todo: TodoItem,
    depth: Int = 0,
    onToggle: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAddSubtask: (Int, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showSubtaskField by remember { mutableStateOf(false) }
    var subtaskTitle by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hasChildren = !todo.children.isNullOrEmpty()
    val completedChildren = todo.children?.count { it.completed } ?: 0
    val totalChildren = todo.children?.size ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (depth == 0) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = todo.completed,
                        onCheckedChange = { onToggle(todo.id) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Purple,
                            uncheckedColor = TextSecondary
                        )
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { expanded = !expanded }
                    ) {
                        Text(
                            text = todo.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (todo.completed) TextSecondary else TextPrimary,
                            textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Due date badge
                            todo.dueDate?.let { dateStr ->
                                val isOverdue = try {
                                    val due = LocalDate.parse(dateStr)
                                    !todo.completed && due.isBefore(LocalDate.now())
                                } catch (e: Exception) { false }

                                val displayDate = try {
                                    LocalDate.parse(dateStr).format(
                                        DateTimeFormatter.ofPattern("MMM d")
                                    )
                                } catch (e: Exception) { dateStr }

                                Badge(
                                    containerColor = if (isOverdue) DangerRed else Purple,
                                    contentColor = TextPrimary
                                ) {
                                    Text(
                                        text = displayDate,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }

                            // Recurrence badge
                            val recurrenceLabel = when {
                                todo.recurrenceInterval != null -> "Every ${todo.recurrenceInterval}d"
                                todo.recurrence != null -> todo.recurrence.replaceFirstChar { it.uppercase() }
                                else -> null
                            }
                            recurrenceLabel?.let {
                                Badge(
                                    containerColor = SurfaceLight,
                                    contentColor = TextSecondary
                                ) {
                                    Text(
                                        text = it,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }

                            // Subtask progress
                            if (hasChildren) {
                                Badge(
                                    containerColor = SurfaceLight,
                                    contentColor = TextSecondary
                                ) {
                                    Text(
                                        text = "$completedChildren/$totalChildren",
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Action buttons
                    if (depth == 0 && !todo.completed) {
                        IconButton(
                            onClick = { showSubtaskField = !showSubtaskField },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add subtask",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Description
                if (expanded && !todo.description.isNullOrBlank()) {
                    Text(
                        text = todo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                    )
                }

                // Progress bar for parent tasks
                if (hasChildren && totalChildren > 0) {
                    LinearProgressIndicator(
                        progress = { completedChildren.toFloat() / totalChildren },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 8.dp, top = 8.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = SuccessGreen,
                        trackColor = SurfaceLight,
                    )
                }

                // Add subtask field
                if (showSubtaskField) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = subtaskTitle,
                            onValueChange = { subtaskTitle = it },
                            placeholder = { Text("New subtask") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = {
                            if (subtaskTitle.isNotBlank()) {
                                onAddSubtask(todo.id, subtaskTitle)
                                subtaskTitle = ""
                                showSubtaskField = false
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Add", tint = Purple)
                        }
                    }
                }
            }
        }

        // Render children
        if (hasChildren) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .background(
                        Purple.copy(alpha = 0.1f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                todo.children!!.forEach { child ->
                    TodoItemCard(
                        todo = child,
                        depth = depth + 1,
                        onToggle = onToggle,
                        onDelete = onDelete,
                        onAddSubtask = onAddSubtask
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Task") },
            text = { Text("Delete \"${todo.title}\"${if (hasChildren) " and all its subtasks" else ""}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(todo.id)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
