package com.todoer.app.data.repository

import com.todoer.app.data.api.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class TodoRepository {

    private var api: ToDoerApi? = null
    private var currentBaseUrl: String = ""

    fun configure(baseUrl: String) {
        if (baseUrl == currentBaseUrl && api != null) return
        currentBaseUrl = baseUrl

        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ToDoerApi::class.java)
    }

    private fun requireApi(): ToDoerApi {
        return api ?: throw IllegalStateException("Server URL not configured")
    }

    suspend fun testConnection(): Boolean {
        return try {
            requireApi().getLists()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getLists(): List<TodoList> = requireApi().getLists()

    suspend fun getTodos(listId: Int): List<TodoItem> = requireApi().getTodos(listId)

    suspend fun createTodo(title: String, listId: Int, description: String? = null, dueDate: String? = null): TodoItem {
        return requireApi().createTodo(CreateTodoRequest(title, description, listId, dueDate))
    }

    suspend fun updateTodo(todoId: Int, title: String? = null, description: String? = null, dueDate: String? = null): TodoItem {
        return requireApi().updateTodo(todoId, UpdateTodoRequest(title, description, dueDate))
    }

    suspend fun toggleTodo(todoId: Int): TodoItem = requireApi().toggleTodo(todoId)

    suspend fun deleteTodo(todoId: Int) = requireApi().deleteTodo(todoId)

    suspend fun addSubtask(parentId: Int, title: String, description: String? = null, dueDate: String? = null): TodoItem {
        return requireApi().addSubtask(parentId, CreateSubtaskRequest(title, description, dueDate))
    }

    suspend fun createList(name: String): TodoList = requireApi().createList(CreateListRequest(name))

    suspend fun deleteList(listId: Int) = requireApi().deleteList(listId)

    suspend fun getAllTodosFlat(): List<TodoItem> {
        val lists = getLists()
        val allTodos = mutableListOf<TodoItem>()
        for (list in lists) {
            val todos = getTodos(list.id)
            allTodos.addAll(flattenTodos(todos))
        }
        return allTodos
    }

    private fun flattenTodos(todos: List<TodoItem>): List<TodoItem> {
        val flat = mutableListOf<TodoItem>()
        for (todo in todos) {
            flat.add(todo)
            todo.children?.let { flat.addAll(flattenTodos(it)) }
        }
        return flat
    }
}
