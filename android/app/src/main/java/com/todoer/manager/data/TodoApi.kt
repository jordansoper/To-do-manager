package com.todoer.manager.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface TodoApiService {
    @GET("api/v1/todos")
    suspend fun getTodos(): TodosResponse

    @POST("api/v1/todos/{id}/toggle")
    suspend fun toggle(@Path("id") id: Int): ToggleResponse
}

object TodoApiFactory {
    fun create(baseUrl: String, apiKey: String?): TodoApiService {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                if (!apiKey.isNullOrBlank()) {
                    b.header("Authorization", "Bearer $apiKey")
                }
                chain.proceed(b.build())
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TodoApiService::class.java)
    }
}
