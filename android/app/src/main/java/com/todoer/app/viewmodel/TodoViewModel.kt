package com.todoer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todoer.app.data.api.TodoItem
import com.todoer.app.data.api.TodoList
import com.todoer.app.data.preferences.ServerPreferences
import com.todoer.app.data.repository.TodoRepository
import com.todoer.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val serverUrl: String = "",
    val notificationsEnabled: Boolean = true,
    val lists: List<TodoList> = emptyList(),
    val currentListId: Int? = null,
    val currentListName: String = "",
    val todos: List<TodoItem> = emptyList(),
    val error: String? = null,
    val screen: Screen = Screen.Setup
)

enum class Screen { Setup, Lists, Todos, Settings }

class TodoViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = ServerPreferences(application)
    private val repo = TodoRepository()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.serverUrl.combine(prefs.notificationsEnabled) { url, notif ->
                Pair(url, notif)
            }.collect { (url, notif) ->
                _state.update { it.copy(serverUrl = url, notificationsEnabled = notif) }
                if (url.isNotBlank()) {
                    repo.configure(url)
                    _state.update { it.copy(isConnected = true, screen = Screen.Lists) }
                    loadLists()
                }
            }
        }
    }

    fun connect(url: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repo.configure(url)
            val success = repo.testConnection()
            if (success) {
                prefs.saveServerUrl(url)
                ReminderScheduler.schedule(getApplication())
                _state.update {
                    it.copy(isLoading = false, isConnected = true, screen = Screen.Lists)
                }
                loadLists()
            } else {
                _state.update {
                    it.copy(isLoading = false, error = "Could not connect to server. Check the URL and try again.")
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            prefs.saveServerUrl("")
            ReminderScheduler.cancel(getApplication())
            _state.update { UiState() }
        }
    }

    fun loadLists() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val lists = repo.getLists()
                _state.update { it.copy(lists = lists, isLoading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Failed to load lists: ${e.message}") }
            }
        }
    }

    fun selectList(list: TodoList) {
        _state.update { it.copy(currentListId = list.id, currentListName = list.name, screen = Screen.Todos) }
        loadTodos()
    }

    fun loadTodos() {
        val listId = _state.value.currentListId ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val todos = repo.getTodos(listId)
                _state.update { it.copy(todos = todos, isLoading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Failed to load tasks: ${e.message}") }
            }
        }
    }

    fun toggleTodo(todoId: Int) {
        viewModelScope.launch {
            try {
                repo.toggleTodo(todoId)
                loadTodos()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to toggle task") }
            }
        }
    }

    fun deleteTodo(todoId: Int) {
        viewModelScope.launch {
            try {
                repo.deleteTodo(todoId)
                loadTodos()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to delete task") }
            }
        }
    }

    fun addTodo(title: String, description: String?, dueDate: String?) {
        val listId = _state.value.currentListId ?: return
        viewModelScope.launch {
            try {
                repo.createTodo(title, listId, description, dueDate)
                loadTodos()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to add task") }
            }
        }
    }

    fun addSubtask(parentId: Int, title: String) {
        viewModelScope.launch {
            try {
                repo.addSubtask(parentId, title)
                loadTodos()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to add subtask") }
            }
        }
    }

    fun createList(name: String) {
        viewModelScope.launch {
            try {
                repo.createList(name)
                loadLists()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to create list") }
            }
        }
    }

    fun deleteList(listId: Int) {
        viewModelScope.launch {
            try {
                repo.deleteList(listId)
                loadLists()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to delete list") }
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    fun changeServer(url: String) {
        connect(url)
    }

    fun toggleNotifications(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setNotificationsEnabled(enabled)
            if (enabled) {
                ReminderScheduler.schedule(getApplication())
            } else {
                ReminderScheduler.cancel(getApplication())
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
