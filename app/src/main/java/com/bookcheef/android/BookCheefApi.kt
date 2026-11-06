package com.bookcheef.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class BookCheefApi(context: Context) {
    private val prefs = context.getSharedPreferences("bookcheef_session", Context.MODE_PRIVATE)
    private val downloads = File(context.filesDir, "books").apply { mkdirs() }
    private val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun hasSession() = prefs.getString("access", null) != null
    fun currentEmail() = prefs.getString("email", "") ?: ""

    private fun request(path: String, method: String = "GET", body: String? = null, auth: Boolean = true, prefer: String? = null): String {
        check(base.startsWith("https://") && key.isNotBlank()) { "Не настроен Supabase. Добавьте SUPABASE_URL и SUPABASE_ANON_KEY в local.properties." }
        val builder = Request.Builder().url("$base$path")
            .header("apikey", key)
            .header("Authorization", "Bearer ${if (auth) prefs.getString("access", key) ?: key else key}")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
        when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(jsonType))
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = try { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } } catch (_: Exception) { text }
                throw IllegalStateException(msg.ifBlank { "HTTP ${response.code}" })
            }
            return text
        }
    }

    suspend fun authenticate(email: String, password: String, register: Boolean) = withContext(Dispatchers.IO) {
        require(email.contains("@")) { "Введите корректную электронную почту." }
        require(password.length >= 6) { "Пароль должен содержать не менее 6 символов." }
        val path = if (register) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val result = JSONObject(request(path, "POST", JSONObject().put("email", email).put("password", password).toString(), false))
        val access = result.optString("access_token")
        if (access.isBlank()) {
            if (register && result.has("id")) throw IllegalStateException("Аккаунт создан. Подтвердите почту, затем войдите.")
            throw IllegalStateException("Сервер не вернул токен. Проверьте подтверждение почты.")
        }
        prefs.edit().putString("access", access).putString("refresh", result.optString("refresh_token"))
            .putString("email", email).putString("user_id", result.optJSONObject("user")?.optString("id")?.takeIf { !it.isNullOrBlank() } ?: result.optString("id")).apply()
    }

    fun logout() {
        try { request("/auth/v1/logout", "POST", "{}", true) } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }

    suspend fun catalog(): List<BookItem> = withContext(Dispatchers.IO) {
        val raw = request("/rest/v1/book_catalog?select=*&order=created_at.desc")
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || id == "null") return@mapNotNull null
            BookItem(id, o.optString("title", "Без названия"), o.optString("description"),
                o.optString("author"), o.optBoolean("is_free", false))
        }
    }

    fun currentUserId() = prefs.getString("user_id", "") ?: ""

    suspend fun readingProgress(bookId: String): JSONObject = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        if (userId.isBlank()) return@withContext JSONObject()
        val raw = request("/rest/v1/user_reading_history?select=reading_progress&user_id=eq.$userId&book_id=eq.$bookId")
        val rows = JSONArray(raw)
        if (rows.length() == 0) {
            val now = java.time.Instant.now().toString()
            val initial = JSONObject().put("user_id", userId).put("book_id", bookId)
                .put("last_opened_at", now).put("completed", false).put("completed_at", JSONObject.NULL)
                .put("reading_progress", JSONObject())
            request("/rest/v1/user_reading_history?on_conflict=user_id,book_id", "POST", initial.toString(), true, "resolution=merge-duplicates,return=minimal")
            JSONObject()
        } else rows.optJSONObject(0)?.optJSONObject("reading_progress") ?: JSONObject()
    }

    suspend fun saveReadingProgress(bookId: String, progress: JSONObject) = withContext(Dispatchers.IO) {
        val userId = currentUserId()
        if (userId.isBlank()) return@withContext
        val now = java.time.Instant.now().toString()
        val payload = JSONObject()
            .put("last_opened_at", now)
            .put("reading_progress", progress.put("updatedAt", now))
        request("/rest/v1/user_reading_history?user_id=eq.$userId&book_id=eq.$bookId", "PATCH", payload.toString())
    }

    suspend fun readBook(id: String, isFree: Boolean): String = withContext(Dispatchers.IO) {
        val file = File(downloads, "$id.json")
        if (isFree && file.exists()) {
            pretty(file.readText())
        } else {
            // Never trust a local paid-book cache: ask Supabase to re-check the subscription on every open.
            if (!isFree) file.delete()
            val result = request("/rest/v1/rpc/get_book_content", "POST", JSONObject().put("target_book_id", id).toString())
            if (isFree) file.writeText(result)
            pretty(result)
        }
    }

    suspend fun downloadBook(id: String, isFree: Boolean) = withContext(Dispatchers.IO) {
        require(isFree) { "Офлайн-загрузка доступна только для бесплатных книг." }
        val result = request("/rest/v1/rpc/get_book_content", "POST", JSONObject().put("target_book_id", id).toString())
        File(downloads, "$id.json").writeText(result)
    }

    fun downloadedIds(): Set<String> = downloads.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    private fun pretty(raw: String): String = try {
        when {
            raw.startsWith("{") -> JSONObject(raw).toString(2)
            raw.startsWith("[") -> JSONArray(raw).toString(2)
            else -> raw
        }
    } catch (_: Exception) { raw }
}
