package com.todoer.app.data.api

import com.google.gson.annotations.SerializedName

data class TodoList(
    val id: Int,
    val name: String,
    @SerializedName("created_at") val createdAt: String
)

data class TodoItem(
    val id: Int,
    val title: String,
    val description: String?,
    val completed: Boolean,
    @SerializedName("due_date") val dueDate: String?,
    val recurrence: String?,
    @SerializedName("recurrence_day") val recurrenceDay: Int?,
    @SerializedName("recurrence_interval") val recurrenceInterval: Int?,
    @SerializedName("list_id") val listId: Int,
    @SerializedName("parent_id") val parentId: Int?,
    @SerializedName("sort_order") val sortOrder: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("completed_at") val completedAt: String?,
    val children: List<TodoItem>?
)

data class CreateTodoRequest(
    val title: String,
    val description: String? = null,
    @SerializedName("list_id") val listId: Int,
    @SerializedName("due_date") val dueDate: String? = null
)

data class CreateSubtaskRequest(
    val title: String,
    val description: String? = null,
    @SerializedName("due_date") val dueDate: String? = null
)

data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    @SerializedName("due_date") val dueDate: String? = null
)

data class CreateListRequest(
    val name: String
)
