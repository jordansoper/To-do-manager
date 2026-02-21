package com.todoer.app.data.api

import retrofit2.http.*

interface ToDoerApi {

    @GET("api/lists")
    suspend fun getLists(): List<TodoList>

    @GET("api/lists/{id}/todos")
    suspend fun getTodos(@Path("id") listId: Int): List<TodoItem>

    @POST("api/todos")
    suspend fun createTodo(@Body todo: CreateTodoRequest): TodoItem

    @PUT("api/todos/{id}")
    suspend fun updateTodo(@Path("id") todoId: Int, @Body todo: UpdateTodoRequest): TodoItem

    @POST("api/todos/{id}/toggle")
    suspend fun toggleTodo(@Path("id") todoId: Int): TodoItem

    @DELETE("api/todos/{id}")
    suspend fun deleteTodo(@Path("id") todoId: Int)

    @POST("api/todos/{id}/subtask")
    suspend fun addSubtask(@Path("id") parentId: Int, @Body subtask: CreateSubtaskRequest): TodoItem

    @POST("api/lists")
    suspend fun createList(@Body list: CreateListRequest): TodoList

    @DELETE("api/lists/{id}")
    suspend fun deleteList(@Path("id") listId: Int)
}
