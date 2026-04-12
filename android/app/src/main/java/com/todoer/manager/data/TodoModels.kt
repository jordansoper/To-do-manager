package com.todoer.manager.data

import com.google.gson.annotations.SerializedName

data class TodosResponse(
    @SerializedName("server_time") val serverTime: String,
    val mode: String? = null,
    val todos: List<TodoNode>? = null,
    val sections: List<TodoSection>? = null,
    @SerializedName("list_id") val listId: Int? = null
)

data class TodoSection(
    val id: Int,
    val name: String,
    val todos: List<TodoNode>
)

data class TodoNode(
    val id: Int,
    val title: String,
    val description: String? = null,
    val completed: Boolean,
    @SerializedName("parent_id") val parentId: Int? = null,
    @SerializedName("list_id") val listId: Int? = null,
    val recurrence: String? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val children: List<TodoNode>? = null
)

data class ListsResponse(
    val lists: List<TodoListItem>
)

data class TodoListItem(
    val id: Int,
    val name: String,
    @SerializedName("sort_order") val sortOrder: Int? = null
)

data class ToggleResponse(
    val ok: Boolean,
    val todo: TodoNode?
)

data class SimpleOkResponse(
    val ok: Boolean? = null,
    val id: Int? = null,
    val error: String? = null
)

data class AddTodoBody(
    val title: String,
    val description: String? = null,
    @SerializedName("list_id") val listId: Int? = null,
    @SerializedName("parent_id") val parentId: Int? = null,
    val recurrence: String? = null,
    @SerializedName("due_date") val dueDate: String? = null
)

data class AddListBody(
    val name: String
)

/** Flattened row for a single task line */
data class FlatRow(
    val id: Int,
    val title: String,
    val depth: Int,
    val completed: Boolean,
    val dueDate: String?,
    val recurrence: String?,
    val hasChildren: Boolean,
    val subDone: Int = 0,
    val subTotal: Int = 0
)

sealed class DisplayRow {
    data class Section(val name: String) : DisplayRow()
    data class Task(val row: FlatRow) : DisplayRow()
}

fun flattenWithCollapse(
    nodes: List<TodoNode>?,
    depth: Int,
    collapsedIds: Set<Int>
): List<FlatRow> {
    if (nodes.isNullOrEmpty()) return emptyList()
    val out = ArrayList<FlatRow>()
    for (n in nodes) {
        val ch = n.children.orEmpty()
        val hasCh = ch.isNotEmpty()
        val done = ch.count { it.completed }
        out.add(
            FlatRow(
                id = n.id,
                title = n.title,
                depth = depth,
                completed = n.completed,
                dueDate = n.dueDate,
                recurrence = n.recurrence,
                hasChildren = hasCh,
                subDone = done,
                subTotal = ch.size
            )
        )
        if (hasCh && n.id !in collapsedIds) {
            out.addAll(flattenWithCollapse(n.children, depth + 1, collapsedIds))
        }
    }
    return out
}

fun buildDisplayRows(
    response: TodosResponse,
    collapsedIds: Set<Int>
): List<DisplayRow> {
    val mode = response.mode ?: "legacy"
    return when (mode) {
        "all" -> {
            val sec = response.sections.orEmpty()
            val out = ArrayList<DisplayRow>()
            for (s in sec) {
                out.add(DisplayRow.Section(s.name))
                flattenWithCollapse(s.todos, 0, collapsedIds).forEach {
                    out.add(DisplayRow.Task(it))
                }
            }
            out
        }
        "single" -> flattenWithCollapse(response.todos, 0, collapsedIds).map { DisplayRow.Task(it) }
        else -> flattenWithCollapse(response.todos, 0, collapsedIds).map { DisplayRow.Task(it) }
    }
}

/** All root todos (for notifications) from any response shape */
fun rootsForNotifications(response: TodosResponse): List<TodoNode> {
    return when (response.mode) {
        "all" -> response.sections.orEmpty().flatMap { it.todos }
        else -> response.todos.orEmpty()
    }
}

data class RootSnap(
    val completed: Boolean,
    val dueDate: String?,
    val recurrence: String?
)

fun rootSnapshots(roots: List<TodoNode>): Map<Int, RootSnap> =
    roots.associate {
        it.id to RootSnap(it.completed, it.dueDate, it.recurrence)
    }

fun collectDueToday(
    nodes: List<TodoNode>?,
    todayIso: String,
    out: MutableList<Pair<Int, String>>
) {
    if (nodes.isNullOrEmpty()) return
    for (n in nodes) {
        if (!n.completed && n.dueDate == todayIso) {
            out.add(n.id to n.title)
        }
        collectDueToday(n.children, todayIso, out)
    }
}
