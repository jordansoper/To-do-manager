package com.todoer.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.todoer.manager.data.Prefs
import com.todoer.manager.data.TodoApiFactory
import com.todoer.manager.data.flattenTodos
import com.todoer.manager.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = TodoAdapter { id -> toggleTodo(id) }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val prefs = Prefs(this)
        binding.editUrl.setText(prefs.baseUrl.orEmpty())
        binding.editApiKey.setText(prefs.apiKey.orEmpty())

        binding.buttonSave.setOnClickListener { saveAndSync() }
        binding.buttonOpenWeb.setOnClickListener { openInBrowser() }
        binding.swipe.setOnRefreshListener { refresh() }

        requestNotifPermissionIfNeeded()
        refresh()
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
        prefs.baseUrl = binding.editUrl.text?.toString()?.trim().orEmpty()
        prefs.apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        TodoSyncWorker.schedule(this)
        refresh()
    }

    private fun openInBrowser() {
        val raw = binding.editUrl.text?.toString()?.trim().orEmpty()
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

    private fun refresh() {
        lifecycleScope.launch {
            binding.swipe.isRefreshing = true
            try {
                val prefs = Prefs(this@MainActivity)
                val base = prefs.baseUrl?.trim().orEmpty()
                if (base.isEmpty()) {
                    adapter.submit(emptyList())
                    return@launch
                }
                val api = TodoApiFactory.create(base, prefs.apiKey?.trim())
                val res = api.getTodos()
                NotificationHelper.processAfterFetch(this@MainActivity, res.todos)
                adapter.submit(flattenTodos(res.todos))
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
                val res = api.getTodos()
                NotificationHelper.processAfterFetch(this@MainActivity, res.todos)
                adapter.submit(flattenTodos(res.todos))
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
}
