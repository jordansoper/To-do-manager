package com.todoer.manager.data

import com.google.gson.annotations.SerializedName

data class TodosResponse(
    @SerializedName("server_time") val serverTime: String,
    val mode: String? = null,
    val todos: List<TodoNode>? = null,
    val sections: List<TodoSection>? = null,
    @SerializedName("list_id") val listId: Int? = null,
    @SerializedName("completed_one_time") val completedOneTime: List<TodoNode>? = null,
    @SerializedName("recurring_waiting") val recurringWaiting: List<TodoNode>? = null
)

data class TodoSection(
    val id: Int,
    val name: String,
    val todos: List<TodoNode>,
    @SerializedName("completed_one_time") val completedOneTime: List<TodoNode>? = null,
    @SerializedName("recurring_waiting") val recurringWaiting: List<TodoNode>? = null
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
    @SerializedName("repeat_date") val repeatDate: String? = null,
    @SerializedName("sub_done") val subDone: Int = 0,
    @SerializedName("sub_total") val subTotal: Int = 0,
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
    @SerializedName("due_date") val dueDate: String? = null,
    @SerializedName("repeat_date") val repeatDate: String? = null
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
    val repeatDate: String?,
    val recurrence: String?,
    val hasChildren: Boolean,
    val subDone: Int = 0,
    val subTotal: Int = 0
)

sealed class DisplayRow {
    data class Section(val name: String) : DisplayRow()
    /** One root task and its visible descendants in a single card (Google Tasks–style). */
    data class TaskGroup(val root: FlatRow, val descendants: List<FlatRow>) : DisplayRow()
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
        val subTotal = if (n.subTotal > 0) n.subTotal else ch.size
        val subDone = if (n.subTotal > 0) n.subDone else ch.count { it.completed }
        out.add(
            FlatRow(
                id = n.id,
                title = n.title,
                depth = depth,
                completed = n.completed,
                dueDate = n.dueDate,
                repeatDate = n.repeatDate,
                recurrence = n.recurrence,
                hasChildren = hasCh,
                subDone = subDone,
                subTotal = subTotal
            )
        )
        if (hasCh && n.id !in collapsedIds) {
            out.addAll(flattenWithCollapse(ch, depth + 1, collapsedIds))
        }
    }
    return out
}

fun buildTaskGroups(nodes: List<TodoNode>?, collapsedIds: Set<Int>): List<DisplayRow> {
    if (nodes.isNullOrEmpty()) return emptyList()
    val out = ArrayList<DisplayRow>()
    for (n in nodes) {
        val ch = n.children.orEmpty()
        val hasCh = ch.isNotEmpty()
        val subTotal = if (n.subTotal > 0) n.subTotal else ch.size
        val subDone = if (n.subTotal > 0) n.subDone else ch.count { it.completed }
        val root = FlatRow(
            id = n.id,
            title = n.title,
            depth = 0,
            completed = n.completed,
            dueDate = n.dueDate,
            repeatDate = n.repeatDate,
            recurrence = n.recurrence,
            hasChildren = hasCh,
            subDone = subDone,
            subTotal = subTotal
        )
        val descendants = if (hasCh && n.id !in collapsedIds) {
            flattenWithCollapse(ch, 1, collapsedIds)
        } else {
            emptyList()
        }
        out.add(DisplayRow.TaskGroup(root, descendants))
    }
    return out
}

private fun bucketTaskGroup(t: TodoNode): DisplayRow.TaskGroup {
    val fr = FlatRow(
        id = t.id,
        title = t.title,
        depth = 0,
        completed = true,
        dueDate = t.dueDate,
        repeatDate = t.repeatDate,
        recurrence = t.recurrence,
        hasChildren = false,
        subDone = 0,
        subTotal = 0
    )
    return DisplayRow.TaskGroup(fr, emptyList())
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
                out.addAll(buildTaskGroups(s.todos, collapsedIds))
                val done = s.completedOneTime.orEmpty()
                if (done.isNotEmpty()) {
                    out.add(DisplayRow.Section("Completed (${done.size})"))
                    for (t in done) out.add(bucketTaskGroup(t))
                }
                val wait = s.recurringWaiting.orEmpty()
                if (wait.isNotEmpty()) {
                    out.add(DisplayRow.Section("Repeating — returns next (${wait.size})"))
                    for (t in wait) out.add(bucketTaskGroup(t))
                }
            }
            out
        }
        "single" -> {
            val out = ArrayList<DisplayRow>()
            out.addAll(buildTaskGroups(response.todos, collapsedIds))
            val done = response.completedOneTime.orEmpty()
            if (done.isNotEmpty()) {
                out.add(DisplayRow.Section("Completed (${done.size})"))
                for (t in done) out.add(bucketTaskGroup(t))
            }
            val wait = response.recurringWaiting.orEmpty()
            if (wait.isNotEmpty()) {
                out.add(DisplayRow.Section("Repeating — returns next (${wait.size})"))
                for (t in wait) out.add(bucketTaskGroup(t))
            }
            out
        }
        else -> buildTaskGroups(response.todos, collapsedIds)
    }
}

/** Root ids of recurring tasks that are completed until repeat_date (not on the active list). */
fun dormantRecurringRootIds(response: TodosResponse): Set<Int> {
    val nodes = when (response.mode) {
        "all" -> response.sections.orEmpty().flatMap { it.recurringWaiting.orEmpty() }
        "single" -> response.recurringWaiting.orEmpty()
        else -> emptyList()
    }
    return nodes.map { it.id }.toSet()
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
