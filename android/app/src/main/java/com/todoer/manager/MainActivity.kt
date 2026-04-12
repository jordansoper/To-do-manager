package com.todoer.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.todoer.manager.data.AddListBody
import com.todoer.manager.data.AddTodoBody
import com.todoer.manager.data.DisplayRow
import com.todoer.manager.data.Prefs
import com.todoer.manager.data.TodoApiFactory
import com.todoer.manager.data.TodoListItem
import com.todoer.manager.data.TodosResponse
import com.todoer.manager.data.buildDisplayRows
import com.todoer.manager.databinding.ActivityMainBinding
import com.todoer.manager.databinding.DialogSubtaskBinding
import com.todoer.manager.databinding.DialogTaskBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var editUrl: TextInputEditText
    private lateinit var editApiKey: TextInputEditText

    private var lastResponse: TodosResponse? = null
    private val collapsedIds = mutableSetOf<Int>()
    private val menuIdToListKey = mutableMapOf<Int, Int>()
    private var cachedLists: List<TodoListItem> = emptyList()
    private var nextMenuItemId = 1

    private val adapter = TodoAdapter(
        collapsedIds,
        onToggle = { id -> toggleTodo(id) },
        onCollapseToggle = { id ->
            if (id in collapsedIds) collapsedIds.remove(id) else collapsedIds.add(id)
            applyDisplay()
        },
        onAddSubtask = { id -> showAddSubtaskDialog(id) }
    )

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val header = binding.navigationView.getHeaderView(0)
        editUrl = header.findViewById(R.id.editUrl)
        editApiKey = header.findViewById(R.id.editApiKey)
        header.findViewById<MaterialButton>(R.id.buttonSave).setOnClickListener { saveAndSync() }
        header.findViewById<MaterialButton>(R.id.buttonOpenWeb).setOnClickListener { openInBrowser() }

        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.nav_drawer_open,
            R.string.nav_drawer_close
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { item ->
            val mid = item.itemId
            val key = menuIdToListKey[mid] ?: return@setNavigationItemSelectedListener false
            when (key) {
                -1 -> {
                    showAddListDialog()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> {
                    Prefs(this).selectedListKey = when (key) {
                        0 -> "all"
                        else -> key.toString()
                    }
                    item.isChecked = true
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    refresh()
                    true
                }
            }
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val prefs = Prefs(this)
        editUrl.setText(prefs.baseUrl.orEmpty())
        editApiKey.setText(prefs.apiKey.orEmpty())

        binding.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        binding.swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface))
        binding.swipe.setOnRefreshListener { refresh() }

        binding.fabAdd.setOnClickListener { showAddTaskDialog() }

        requestNotifPermissionIfNeeded()
        refresh()
    }

    private fun rebuildNavMenu(lists: List<TodoListItem>) {
        cachedLists = lists
        val menu = binding.navigationView.menu
        menu.clear()
        menuIdToListKey.clear()
        nextMenuItemId = 1
        fun addEntry(title: String, key: Int): Int {
            val id = nextMenuItemId++
            val item = menu.add(Menu.NONE, id, Menu.NONE, title)
            item.isCheckable = true
            menuIdToListKey[id] = key
            return id
        }
        addEntry(getString(R.string.nav_all_tasks), 0)
        lists.forEach { addEntry(it.name, it.id) }
        addEntry(getString(R.string.nav_add_list), -1)
        markNavSelection()
    }

    private fun markNavSelection() {
        val key = Prefs(this).selectedListKey
        val want = when (key) {
            "all" -> 0
            else -> key.toIntOrNull() ?: 1
        }
        val menu = binding.navigationView.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val k = menuIdToListKey[item.itemId] ?: continue
            item.isChecked = when (k) {
                -1 -> false
                else -> k == want
            }
        }
        updateToolbarSubtitle()
    }

    private fun updateToolbarSubtitle() {
        val key = Prefs(this).selectedListKey
        supportActionBar?.subtitle = when (key) {
            "all" -> getString(R.string.nav_all_tasks)
            else -> cachedLists.find { it.id.toString() == key }?.name ?: key
        }
    }

    override fun onResume() {
        super.onResume()
        TodoSyncWorker.schedule(this)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> { }
                else -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun saveAndSync() {
        val prefs = Prefs(this)
        prefs.baseUrl = editUrl.text?.toString()?.trim().orEmpty()
        prefs.apiKey = editApiKey.text?.toString()?.trim().orEmpty()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        TodoSyncWorker.schedule(this)
        refresh()
    }

    private fun openInBrowser() {
        val raw = editUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a server URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
        if (uri == null || uri.scheme.isNullOrBlank()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun applyDisplay() {
        val res = lastResponse ?: return
        val rows = buildDisplayRows(res, collapsedIds)
        adapter.submit(rows)
        val hasTasks = rows.any { it is DisplayRow.TaskGroup }
        binding.emptyState.isVisible = !hasTasks
        if (!hasTasks) {
            binding.emptyState.setText(R.string.empty_no_tasks)
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            binding.swipe.isRefreshing = true
            try {
                val prefs = Prefs(this@MainActivity)
                val base = prefs.baseUrl?.trim().orEmpty()
                if (base.isEmpty()) {
                    lastResponse = null
                    adapter.submit(emptyList())
                    binding.recycler.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                    binding.emptyState.setText(R.string.empty_no_url)
                    binding.fabAdd.isVisible = false
                    return@launch
                }
                binding.recycler.visibility = View.VISIBLE
                binding.fabAdd.isVisible = true

                val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                val listsRes = api.getLists()
                rebuildNavMenu(listsRes.lists)

                val listKey = prefs.selectedListKey
                val res = api.getTodos(listKey)
                lastResponse = res
                NotificationHelper.processAfterFetch(this@MainActivity, res)
                applyDisplay()
                updateToolbarSubtitle()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Sync failed",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun toggleTodo(id: Int) {
        lifecycleScope.launch {
            try {
                val prefs = Prefs(this@MainActivity)
                val base = prefs.baseUrl?.trim().orEmpty()
                if (base.isEmpty()) return@launch
                val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                api.toggle(id)
                val listKey = prefs.selectedListKey
                val res = api.getTodos(listKey)
                lastResponse = res
                NotificationHelper.processAfterFetch(this@MainActivity, res)
                applyDisplay()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Toggle failed",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }
    }

    private fun showAddTaskDialog() {
        val prefs = Prefs(this)
        val base = prefs.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return

        val dialogBinding = DialogTaskBinding.inflate(layoutInflater)
        val recOptions = listOf("", "daily", "weekly", "monthly", "yearly")
        val recLabels = listOf(
            getString(R.string.recurrence_none),
            getString(R.string.recurrence_daily),
            getString(R.string.recurrence_weekly),
            getString(R.string.recurrence_monthly),
            getString(R.string.recurrence_yearly)
        )
        dialogBinding.spinnerRecurrence.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            recLabels
        )

        val showListPicker = prefs.selectedListKey == "all"
        dialogBinding.labelList.isVisible = showListPicker
        dialogBinding.spinnerList.isVisible = showListPicker
        if (showListPicker && cachedLists.isNotEmpty()) {
            dialogBinding.spinnerList.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                cachedLists.map { it.name }
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_task)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = dialogBinding.inputTitle.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                        val recIdx = dialogBinding.spinnerRecurrence.selectedItemPosition
                        val recurrence = recOptions.getOrNull(recIdx)?.takeIf { it.isNotEmpty() }
                        val due = dialogBinding.inputDue.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
                        val resolvedListId = if (showListPicker && cachedLists.isNotEmpty()) {
                            cachedLists[dialogBinding.spinnerList.selectedItemPosition].id
                        } else {
                            prefs.selectedListKey.toIntOrNull()
                        }
                        api.addTodo(
                            AddTodoBody(
                                title = title,
                                description = dialogBinding.inputDescription.text?.toString()?.trim()
                                    ?.takeIf { it.isNotEmpty() },
                                listId = resolvedListId,
                                recurrence = recurrence,
                                dueDate = due
                            )
                        )
                        refresh()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message ?: "Failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddSubtaskDialog(parentId: Int) {
        val prefs = Prefs(this)
        val base = prefs.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return
        val d = DialogSubtaskBinding.inflate(layoutInflater)
        val recOptions = listOf("", "daily", "weekly", "monthly", "yearly")
        val recLabels = listOf(
            getString(R.string.recurrence_none),
            getString(R.string.recurrence_daily),
            getString(R.string.recurrence_weekly),
            getString(R.string.recurrence_monthly),
            getString(R.string.recurrence_yearly)
        )
        d.spinnerRecurrence.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            recLabels
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_subtask)
            .setView(d.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = d.inputTitle.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                        val recIdx = d.spinnerRecurrence.selectedItemPosition
                        val recurrence = recOptions.getOrNull(recIdx)?.takeIf { it.isNotEmpty() }
                        val due = d.inputDue.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
                        api.addTodo(
                            AddTodoBody(
                                title = title,
                                parentId = parentId,
                                recurrence = recurrence,
                                dueDate = due
                            )
                        )
                        refresh()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message ?: "Failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddListDialog() {
        val prefs = Prefs(this)
        val base = prefs.baseUrl?.trim().orEmpty()
        if (base.isEmpty()) return
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.new_list_title)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nav_add_list)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                        api.addList(AddListBody(name))
                        refresh()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message ?: "Failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
