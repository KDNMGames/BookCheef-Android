package com.bookcheef.android

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BookCheefApi(context: Context) {
    private val prefs = context.getSharedPreferences("bookcheef_session", Context.MODE_PRIVATE)
    private val downloads = File(context.filesDir, "books").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun hasSession() = prefs.getString("access", null) != null
    fun currentEmail() = prefs.getString("email", "") ?: ""
    fun currentUserId() = prefs.getString("user_id", "") ?: ""

    private fun request(path: String, method: String = "GET", body: String? = null, auth: Boolean = true, prefer: String? = null): String {
        check(base.startsWith("https://") && key.isNotBlank()) {
            "Не настроен Supabase. Проверьте SUPABASE_URL и SUPABASE_ANON_KEY в настройках сборки."
        }
        val token = if (auth) prefs.getString("access", null)?.takeIf { it.isNotBlank() } ?: key else key
        val builder = Request.Builder().url("$base$path")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
        when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(jsonType))
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    val json = JSONObject(responseText)
                    listOf("message", "msg", "error_description", "error", "hint", "details")
                        .map { json.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
                } catch (_: Exception) { null }
                throw IllegalStateException(message ?: "Ошибка Supabase HTTP ${response.code}: ${responseText.take(240)}")
            }
            return responseText
        }
    }

    private suspend fun refreshIfNeeded() = withContext(Dispatchers.IO) {
        val access = prefs.getString("access", null) ?: return@withContext
        val refresh = prefs.getString("refresh", null) ?: return@withContext
        val expired = try {
            val payload = access.split(".").getOrNull(1) ?: ""
            val json = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
            json.optLong("exp", 0L) <= System.currentTimeMillis() / 1000L + 90L
        } catch (_: Exception) { false }
        if (!expired) return@withContext
        val result = JSONObject(request(
            "/auth/v1/token?grant_type=refresh_token",
            "POST",
            JSONObject().put("refresh_token", refresh).toString(),
            false
        ))
        val newAccess = result.optString("access_token")
        if (newAccess.isBlank()) throw IllegalStateException("Сессия истекла. Войдите в BookCheef заново.")
        prefs.edit()
            .putString("access", newAccess)
            .putString("refresh", result.optString("refresh_token", refresh))
            .apply()
    }

    suspend fun authenticate(email: String, password: String, register: Boolean) = withContext(Dispatchers.IO) {
        require(email.contains("@")) { "Введите корректную электронную почту." }
        require(password.length >= 6) { "Пароль должен содержать не менее 6 символов." }
        val path = if (register) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val result = JSONObject(request(path, "POST", JSONObject().put("email", email).put("password", password).toString(), false))
        val access = result.optString("access_token")
        if (access.isBlank()) {
            if (register && (result.has("id") || result.has("user"))) {
                throw IllegalStateException("Аккаунт создан. Подтвердите почту по ссылке из письма, затем войдите.")
            }
            throw IllegalStateException("Сервер не вернул токен. Проверьте подтверждение почты.")
        }
        val user = result.optJSONObject("user")
        prefs.edit()
            .putString("access", access)
            .putString("refresh", result.optString("refresh_token"))
            .putString("email", user?.optString("email")?.takeIf { !it.isNullOrBlank() } ?: email)
            .putString("user_id", user?.optString("id")?.takeIf { !it.isNullOrBlank() } ?: result.optString("id"))
            .apply()
    }

    fun logout() {
        try { request("/auth/v1/logout", "POST", "{}", true) } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }

    suspend fun catalog(): List<BookItem> = withContext(Dispatchers.IO) {
        refreshIfNeeded()
        // Use the same public catalog view as the website. Keep the request explicit so
        // PostgREST only returns columns needed by the Android gallery.
        val raw = request(
            "/rest/v1/book_catalog?select=*&order=created_at.desc",
            auth = true
        )
        val arr = try { JSONArray(raw) } catch (e: Exception) {
            throw IllegalStateException("Supabase вернул каталог в неожиданном формате: ${raw.take(220)}", e)
        }
        (0 until arr.length()).mapNotNull { index ->
            val row = arr.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
            val cover = row.optString("cover_url").takeUnless { it.isBlank() || it == "null" }
                ?: row.optString("cover").takeUnless { it.isBlank() || it == "null" }
            BookItem(
                id = id,
                title = row.optString("title").ifBlank { "Без названия" },
                description = row.optString("description"),
                author = row.optString("author"),
                isFree = row.optBoolean("is_free", false),
                coverUrl = publicImageUrl(cover),
                age = row.optString("age"),
                genre = row.optString("genre"),
                series = row.optString("series"),
                isInteractive = row.optBoolean("is_interactive", false)
            )
        }
    }

    private fun publicImageUrl(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return null
        if (raw.startsWith("https://") || raw.startsWith("http://") || raw.startsWith("data:image/")) return raw
        val path = raw.removePrefix("/")
        return when {
            path.startsWith("storage/v1/") -> "$base/$path"
            path.startsWith("gallery/") -> "$base/storage/v1/object/public/$path"
            else -> raw
        }
    }

    suspend fun readingProgress(bookId: String): JSONObject = withContext(Dispatchers.IO) {
        refreshIfNeeded()
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
        refreshIfNeeded()
        val userId = currentUserId()
        if (userId.isBlank()) return@withContext
        val now = java.time.Instant.now().toString()
        val payload = JSONObject()
            .put("last_opened_at", now)
            .put("reading_progress", progress.put("updatedAt", now))
        request("/rest/v1/user_reading_history?user_id=eq.$userId&book_id=eq.$bookId", "PATCH", payload.toString())
    }

    suspend fun readBook(id: String, isFree: Boolean): String = withContext(Dispatchers.IO) {
        refreshIfNeeded()
        val file = File(downloads, "$id.json")
        if (isFree && file.exists()) return@withContext pretty(file.readText())

        // Paid books are never served from a local cache; the RPC checks entitlement each time.
        if (!isFree) file.delete()
        val response = request(
            "/rest/v1/rpc/get_book_content",
            "POST",
            JSONObject().put("target_book_id", id).toString()
        )
        val book = try { JSONObject(response) } catch (_: Exception) { null }
        val content = book?.optString("book_content").orEmpty()
        val result = when {
            content.isNotBlank() -> content
            book?.optJSONObject("interactive_content") != null -> {
                val data = book.optJSONObject("interactive_content")!!
                val nodes = data.optJSONArray("nodes") ?: JSONArray()
                buildString {
                    for (i in 0 until nodes.length()) {
                        val node = nodes.optJSONObject(i) ?: continue
                        val heading = node.optString("title").ifBlank { node.optString("id") }
                        if (heading.isNotBlank()) append(heading).append("\n\n")
                        append(node.optString("text")).append("\n\n")
                        val choices = node.optJSONArray("choices") ?: JSONArray()
                        for (j in 0 until choices.length()) append("• ").append(choices.optJSONObject(j)?.optString("label").orEmpty()).append("\n")
                        append("\n")
                    }
                }.trim()
            }
            else -> "Для этой книги пока не добавлен текст. Попробуйте открыть её на сайте или обратитесь к автору."
        }
        if (isFree) file.writeText(JSONObject().put("book_content", result).toString())
        result
    }

    suspend fun downloadBook(id: String, isFree: Boolean) = withContext(Dispatchers.IO) {
        require(isFree) { "Офлайн-загрузка доступна только для бесплатных книг." }
        refreshIfNeeded()
        val result = request("/rest/v1/rpc/get_book_content", "POST", JSONObject().put("target_book_id", id).toString())
        val book = JSONObject(result)
        File(downloads, "$id.json").writeText(book.toString())
    }

    fun downloadedIds(): Set<String> =
        downloads.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    private fun pretty(raw: String): String = try {
        val obj = JSONObject(raw)
        obj.optString("book_content").ifBlank { obj.toString(2) }
    } catch (_: Exception) {
        try { JSONArray(raw).toString(2) } catch (_: Exception) { raw }
    }
}

data class BookItem(
    val id: String,
    val title: String,
    val description: String,
    val author: String,
    val isFree: Boolean,
    val coverUrl: String? = null,
    val age: String = "",
    val genre: String = "",
    val series: String = "",
    val isInteractive: Boolean = false
)
