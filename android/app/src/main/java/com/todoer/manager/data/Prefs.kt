package com.todoer.manager.data

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("todoer", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = p.getString("base_url", null)
        set(value) { p.edit().putString("base_url", value).apply() }

    var apiKey: String?
        get() = p.getString("api_key", null)
        set(value) { p.edit().putString("api_key", value).apply() }

    /** "all" or list id as string, e.g. "1" */
    var selectedListKey: String
        get() = p.getString("selected_list", "1") ?: "1"
        set(value) { p.edit().putString("selected_list", value).apply() }

    fun lastRootSnapshotJson(): String? = p.getString("root_snapshot_json", null)

    fun setLastRootSnapshotJson(json: String?) {
        p.edit().putString("root_snapshot_json", json).apply()
    }

    fun dueNotifiedDate(): String? = p.getString("due_notif_date", null)

    fun dueNotifiedIds(): Set<String> =
        p.getStringSet("due_notif_ids", emptySet()) ?: emptySet()

    fun markDueNotified(todayIso: String, ids: Set<String>) {
        p.edit()
            .putString("due_notif_date", todayIso)
            .putStringSet("due_notif_ids", ids)
            .apply()
    }

    /** Root todo ids (strings) that are completed recurring until repeat_date. */
    fun lastDormantRecurringIds(): Set<String> =
        p.getStringSet("dormant_recurring_ids", emptySet()) ?: emptySet()

    fun setLastDormantRecurringIds(ids: Set<String>) {
        p.edit().putStringSet("dormant_recurring_ids", ids).apply()
    }
}
