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
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class BookCheefApi(context: Context) {
    private val prefs = context.getSharedPreferences("bookcheef_session", Context.MODE_PRIVATE)
    private val downloads = File(context.filesDir, "books").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun hasSession() = prefs.getString("access", null) != null
    fun currentEmail() = prefs.getString("email", "") ?: ""
    fun currentUserId() = prefs.getString("user_id", "") ?: ""

    private fun accessToken(): String = prefs.getString("access", null) ?: key

    private fun request(path: String, method: String = "GET", body: String? = null, auth: Boolean = true, prefer: String? = null): String {
        check(base.startsWith("https://") && key.isNotBlank()) {
            "Не настроен Supabase. Проверьте SUPABASE_URL и SUPABASE_ANON_KEY в настройках сборки."
        }
        val builder = Request.Builder().url("$base$path")
            .header("apikey", key)
            .header("Authorization", "Bearer ${if (auth) accessToken() else key}")
        if (!prefer.isNullOrBlank()) builder.header("Prefer", prefer)
        when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(jsonType))
            "DELETE" -> builder.delete((body ?: "{}").toRequestBody(jsonType))
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                val msg = json.optString("msg").ifBlank {
                    json.optString("message").ifBlank { json.optString("error_description") }
                }
                throw IllegalStateException(msg.ifBlank { "Ошибка Supabase HTTP ${response.code}: ${text.take(240)}" })
            }
            return text
        }
    }

    private fun requestBytes(path: String): ByteArray {
        check(base.startsWith("https://") && key.isNotBlank()) { "Не настроен Supabase." }
        val builder = Request.Builder().url("$base$path")
            .header("apikey", key)
            .header("Authorization", "Bearer ${accessToken()}")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                val msg = json.optString("message").ifBlank { json.optString("error").ifBlank { text } }
                throw IllegalStateException("Не удалось загрузить файл книги (HTTP ${response.code}): ${msg.take(220)}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
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
            .putString("email", user?.optString("email")?.ifBlank { email } ?: email)
            .putString("user_id", user?.optString("id")?.takeIf { it.isNotBlank() } ?: result.optString("id"))
            .apply()
    }

    fun logout() {
        try { request("/auth/v1/logout", "POST", "{}", true) } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }

    suspend fun catalog(): List<BookItem> = withContext(Dispatchers.IO) {
        // book_catalog is the public metadata view: it exposes covers and descriptions without book text.
        val raw = request("/rest/v1/book_catalog?select=id,title,description,cover_url,author,age,genre,series,is_free,is_interactive,created_at&order=created_at.desc")
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || id == "null") return@mapNotNull null
            BookItem(
                id = id,
                title = o.optString("title").ifBlank { "Без названия" },
                description = o.optString("description"),
                author = o.optString("author"),
                isFree = o.optBoolean("is_free", false),
                coverUrl = o.optString("cover_url").takeIf { it.startsWith("https://") || it.startsWith("http://") },
                age = o.optString("age"),
                genre = o.optString("genre"),
                series = o.optString("series"),
                isInteractive = o.optBoolean("is_interactive", false)
            )
        }
    }

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
        val cached = File(downloads, "$id.txt")
        if (isFree && cached.exists()) return@withContext cached.readText()
        // Always ask the protected RPC for the full row. It checks the Pro entitlement on the server.
        if (!isFree) cached.delete()
        val result = request("/rest/v1/rpc/get_book_content", "POST", JSONObject().put("target_book_id", id).toString())
        val row = try { JSONObject(result) } catch (_: Exception) {
            throw IllegalStateException("Сервер вернул некорректные данные книги.")
        }
        val text = row.optString("book_content")
        if (text.isNotBlank()) {
            if (isFree) cached.writeText(text)
            return@withContext text
        }

        val filePath = row.optString("book_file_path")
        if (filePath.isBlank() || filePath == "null") {
            throw IllegalStateException("У этой книги пока нет текста или загруженного файла.")
        }
        val encodedPath = filePath.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val bytes = requestBytes("/storage/v1/object/authenticated/book-files/$encodedPath")
        val fileType = row.optString("book_file_type").lowercase()
        val documentText = when (fileType) {
            "txt", "md", "markdown", "html", "htm", "rtf" -> bytes.toString(Charsets.UTF_8).let { decodeDocumentText(it, fileType) }
            "docx" -> extractDocxText(bytes)
            "pdf" -> throw IllegalStateException("Книга загружена в PDF. Для чтения PDF внутри приложения нужен отдельный просмотрщик; файл успешно найден, но этот формат пока не отображается как обычный текст.")
            else -> throw IllegalStateException("Формат .$fileType пока не поддерживается встроенным текстовым просмотрщиком.")
        }
        if (documentText.isBlank()) throw IllegalStateException("Файл книги загружен, но в нём не найден читаемый текст.")
        if (isFree) cached.writeText(documentText)
        documentText
    }

    suspend fun downloadBook(id: String, isFree: Boolean) = withContext(Dispatchers.IO) {
        require(isFree) { "Офлайн-загрузка доступна только для бесплатных книг." }
        val result = request("/rest/v1/rpc/get_book_content", "POST", JSONObject().put("target_book_id", id).toString())
        val row = JSONObject(result)
        val text = row.optString("book_content")
        val content = if (text.isNotBlank()) text else {
            val path = row.optString("book_file_path")
            require(path.isNotBlank() && path != "null") { "У книги нет текста или файла." }
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val bytes = requestBytes("/storage/v1/object/authenticated/book-files/$encodedPath")
            when (row.optString("book_file_type").lowercase()) {
                "txt", "md", "markdown", "html", "htm", "rtf" -> decodeDocumentText(bytes.toString(Charsets.UTF_8), row.optString("book_file_type").lowercase())
                "docx" -> extractDocxText(bytes)
                else -> throw IllegalStateException("Для офлайн-чтения пока поддерживаются TXT, Markdown, HTML, RTF и DOCX.")
            }
        }
        File(downloads, "$id.txt").writeText(content)
    }

    fun downloadedIds(): Set<String> = downloads.listFiles()?.filter { it.extension == "txt" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    private fun decodeDocumentText(raw: String, type: String): String {
        if (type == "html" || type == "htm") {
            return raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
        }
        if (type == "rtf") return raw.replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace(Regex("[{}]"), "").trim()
        return raw
    }

    private fun extractDocxText(bytes: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    found = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
            found
        }
        if (xml.isBlank()) throw IllegalStateException("Не удалось прочитать текст DOCX-файла.")
        return xml.replace(Regex("</w:p>"), "\n\n")
            .replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .trim()
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
