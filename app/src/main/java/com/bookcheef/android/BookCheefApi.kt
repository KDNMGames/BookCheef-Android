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
import java.io.ByteArrayInputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BookCheefApi(context: Context) {
    private val appContext = context.applicationContext
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
    fun hasCompletedLogin() = prefs.getBoolean("login_completed", false)
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
            .putBoolean("login_completed", true)
            .apply()
    }

    fun logout() {
        try { request("/auth/v1/logout", "POST", "{}", true) } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }

    suspend fun catalog(): List<BookItem> = withContext(Dispatchers.IO) {
        // A previously signed-in reader may open the app without a network connection.
        // Prefer a fresh catalog, but keep the last successful public catalog locally.
        val raw = try {
            refreshIfNeeded()
            request("/rest/v1/book_catalog?select=*&order=created_at.desc", auth = true).also {
                prefs.edit().putString("catalog_cache", it).apply()
            }
        } catch (networkOrSessionError: Exception) {
            prefs.getString("catalog_cache", null)
                ?: throw IllegalStateException("Не удалось загрузить каталог. Подключитесь к интернету хотя бы один раз, чтобы сохранить библиотеку на устройстве.", networkOrSessionError)
        }
        val arr = try { JSONArray(raw) } catch (e: Exception) {
            throw IllegalStateException("Supabase вернул каталог в неожиданном формате: ${raw.take(220)}", e)
        }
        val ratingByBook = try {
            val ratings = JSONArray(request("/rest/v1/rpc/get_book_rating_summary", "POST", "{}"))
            (0 until ratings.length()).mapNotNull { i ->
                ratings.optJSONObject(i)?.let { row -> row.optString("book_id") to (row.optInt("likes") to row.optInt("dislikes")) }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
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
                isInteractive = row.optBoolean("is_interactive", false),
                likes = ratingByBook[id]?.first ?: 0,
                dislikes = ratingByBook[id]?.second ?: 0
            )
        }
    }

    suspend fun catalogCollections(): List<BookCollection> = withContext(Dispatchers.IO) {
        val raw = try {
            request("/rest/v1/site_settings?select=data&id=eq.1", auth = false).also {
                prefs.edit().putString("collections_cache", it).apply()
            }
        } catch (_: Exception) {
            prefs.getString("collections_cache", null) ?: return@withContext emptyList()
        }
        val rows = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val settings = rows.optJSONObject(0)?.optJSONObject("data") ?: JSONObject()
        val items = settings.optJSONArray("catalog_collections") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank() || id == "null") return@mapNotNull null
            val ids = item.optJSONArray("book_ids") ?: JSONArray()
            BookCollection(
                id = id,
                name = item.optString("name").ifBlank { "Подборка" },
                description = item.optString("description"),
                coverUrl = publicImageUrl(item.optString("cover_url")),
                bookIds = (0 until ids.length()).map { ids.optString(it) }.toSet()
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
            else -> "$base/storage/v1/object/public/gallery/$path"
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

    private fun requestBytes(path: String): ByteArray {
        val token = prefs.getString("access", null)?.takeIf { it.isNotBlank() } ?: key
        val request = Request.Builder().url("$base$path")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = response.body?.string().orEmpty()
                throw IllegalStateException("Не удалось загрузить файл книги (HTTP " + response.code + "): " + message.take(220))
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val xml = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var result = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    result = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
            result
        }
        if (xml.isBlank()) throw IllegalStateException("Не удалось прочитать содержимое DOCX-файла.")
        return xml.replace(Regex("</w:p>"), "\n\n")
            .replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").trim()
    }

    private fun extractDocumentText(bytes: ByteArray, type: String): String {
        return when (type) {
            "txt", "md", "markdown" -> bytes.toString(Charsets.UTF_8)
            "html", "htm" -> bytes.toString(Charsets.UTF_8)
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
            "rtf" -> bytes.toString(Charsets.UTF_8)
                .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
                .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
                .replace(Regex("[{}]"), "").trim()
            "docx" -> extractDocx(bytes)
            "pdf" -> {
                PDFBoxResourceLoader.init(appContext)
                PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                    PDFTextStripper().getText(document).trim()
                }
            }
            else -> throw IllegalStateException("Формат .$type пока не поддерживается. Поддерживаются TXT, Markdown, HTML, RTF, DOCX и PDF.")
        }
    }

    private suspend fun fetchBookResult(id: String): BookReadResult {
        val response = request(
            "/rest/v1/rpc/get_book_content",
            "POST",
            JSONObject().put("target_book_id", id).toString()
        )
        val book = try { JSONObject(response) } catch (_: Exception) {
            throw IllegalStateException("Сервер вернул некорректные данные книги.")
        }
        var content = book.optString("book_content").takeUnless { it == "null" }.orEmpty()
        val interactive = book.optJSONObject("interactive_content")
        val questions = book.optJSONArray("reader_questions") ?: JSONArray()
        if (content.isBlank() && interactive == null) {
            val filePath = book.optString("book_file_path")
            if (filePath.isBlank() || filePath == "null") {
                throw IllegalStateException("У этой книги нет текста и не прикреплён файл. Проверьте книгу в панели администратора сайта.")
            }
            val encodedPath = filePath.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val bytes = requestBytes("/storage/v1/object/authenticated/book-files/$encodedPath")
            val type = book.optString("book_file_type").lowercase().ifBlank {
                book.optString("book_file_name").substringAfterLast('.', "").lowercase()
            }
            content = extractDocumentText(bytes, type)
            if (content.isBlank()) throw IllegalStateException("Файл получен, но в нём не найден текст. Возможно, PDF является сканом без распознаваемого текста.")
        }
        return BookReadResult(
            text = content,
            interactiveContent = interactive,
            readerQuestions = questions,
            footnotes = interactive?.optJSONArray("footnotes") ?: JSONArray(),
            footnoteStyle = interactive?.optJSONObject("footnote_style") ?: JSONObject()
        )
    }

    suspend fun readBook(id: String, isFree: Boolean): BookReadResult = withContext(Dispatchers.IO) {
        val file = File(downloads, "$id.json")
        // Offline downloads are self-contained and must open before any token refresh.
        if (isFree && file.exists()) {
            try {
                val cached = JSONObject(file.readText())
                val text = cached.optString("text")
                if (text.isNotBlank() || cached.optJSONObject("interactiveContent") != null) {
                    return@withContext BookReadResult(
                        text = text,
                        interactiveContent = cached.optJSONObject("interactiveContent"),
                        readerQuestions = cached.optJSONArray("readerQuestions") ?: JSONArray(),
                        footnotes = cached.optJSONArray("footnotes") ?: JSONArray(),
                        footnoteStyle = cached.optJSONObject("footnoteStyle") ?: JSONObject()
                    )
                }
            } catch (_: Exception) { file.delete() }
        }
        if (!isFree) file.delete()
        refreshIfNeeded()
        val result = fetchBookResult(id)
        if (isFree) file.writeText(JSONObject()
            .put("text", result.text)
            .put("interactiveContent", result.interactiveContent ?: JSONObject.NULL)
            .put("readerQuestions", result.readerQuestions)
            .put("footnotes", result.footnotes)
            .put("footnoteStyle", result.footnoteStyle)
            .toString())
        result
    }

    suspend fun downloadBook(id: String, isFree: Boolean) = withContext(Dispatchers.IO) {
        require(isFree) { "Офлайн-загрузка доступна только для бесплатных книг." }
        refreshIfNeeded()
        val result = fetchBookResult(id)
        File(downloads, "$id.json").writeText(JSONObject()
            .put("text", result.text)
            .put("interactiveContent", result.interactiveContent ?: JSONObject.NULL)
            .put("readerQuestions", result.readerQuestions)
            .put("footnotes", result.footnotes)
            .put("footnoteStyle", result.footnoteStyle)
            .toString())
    }

    fun downloadedIds(): Set<String> =
        downloads.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    fun deleteDownloadedBook(id: String): Boolean {
        val file = File(downloads, "$id.json")
        return !file.exists() || file.delete()
    }

    fun downloadedSizeBytes(): Long =
        downloads.listFiles()?.filter { it.isFile && it.extension == "json" }?.sumOf { it.length() } ?: 0L

    private fun pretty(raw: String): String = try {
        val obj = JSONObject(raw)
        obj.optString("book_content").ifBlank { obj.toString(2) }
    } catch (_: Exception) {
        try { JSONArray(raw).toString(2) } catch (_: Exception) { raw }
    }
}

data class BookCollection(
    val id: String,
    val name: String,
    val description: String,
    val coverUrl: String?,
    val bookIds: Set<String>
)

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
    val isInteractive: Boolean = false,
    val likes: Int = 0,
    val dislikes: Int = 0
)


data class BookReadResult(
    val text: String = "",
    val interactiveContent: JSONObject? = null,
    val readerQuestions: JSONArray = JSONArray(),
    val footnotes: JSONArray = JSONArray(),
    val footnoteStyle: JSONObject = JSONObject()
)
