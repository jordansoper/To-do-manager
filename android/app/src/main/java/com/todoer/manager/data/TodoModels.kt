package com.todoer.manager.data

import com.google.gson.annotations.SerializedName

data class TodosResponse(
    @SerializedName("server_time") val serverTime: String,
    val todos: List<TodoNode>
)

data class TodoNode(
    val id: Int,
    val title: String,
    val description: String? = null,
    val completed: Boolean,
    @SerializedName("parent_id") val parentId: Int? = null,
    val recurrence: String? = null,
    @SerializedName("due_date") val dueDate: String? = null,
    val children: List<TodoNode>? = null
)

data class ToggleResponse(
    val ok: Boolean,
    val todo: TodoNode?
)

data class FlatRow(
    val id: Int,
    val title: String,
    val depth: Int,
    val completed: Boolean,
    val dueDate: String?,
    val recurrence: String?
)

fun flattenTodos(nodes: List<TodoNode>?, depth: Int = 0): List<FlatRow> {
    if (nodes.isNullOrEmpty()) return emptyList()
    val out = ArrayList<FlatRow>()
    for (n in nodes) {
        out.add(
            FlatRow(
                id = n.id,
                title = n.title,
                depth = depth,
                completed = n.completed,
                dueDate = n.dueDate,
                recurrence = n.recurrence
            )
        )
        out.addAll(flattenTodos(n.children, depth + 1))
    }
    return out
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
