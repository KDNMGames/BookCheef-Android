package com.bookcheef.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.snapshotFlow
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BookCheefApp() } }
    }
}

@Composable
private fun BookCheefApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember { BookCheefApi(context) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(api.hasSession()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    var selected by remember { mutableStateOf<BookItem?>(null) }
    var readingText by remember { mutableStateOf("") }
    val readerScrollState = rememberScrollState()
    var downloaded by remember { mutableStateOf(setOf<String>()) }
    var tab by remember { mutableStateOf("catalog") }
    val preferences = remember { context.getSharedPreferences("bookcheef_preferences", android.content.Context.MODE_PRIVATE) }
    var textScale by remember { mutableFloatStateOf(preferences.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.8f)) }

    fun loadCatalog() {
        scope.launch {
            busy = true; error = ""
            try {
                books = api.catalog()
                downloaded = api.downloadedIds()
            } catch (e: Exception) { error = e.message ?: "Не удалось загрузить каталог" }
            busy = false
        }
    }
    LaunchedEffect(session) { if (session) loadCatalog() }

    if (!session) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(24.dp).wrapContentHeight(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("BookCheef", style = MaterialTheme.typography.headlineLarge)
                Text(if (register) "Создать аккаунт" else "Войти в аккаунт", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(email, { email = it }, label = { Text("Электронная почта") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    scope.launch {
                        busy = true; error = ""
                        try { api.authenticate(email.trim(), password, register); session = api.hasSession() }
                        catch (e: Exception) { error = e.message ?: "Ошибка авторизации" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && password.length >= 6) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (register) "Зарегистрироваться" else "Войти")
                }
                TextButton(onClick = { register = !register; error = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
                }
                Text("Для доступа к библиотеке необходим аккаунт BookCheef. Используйте тот же аккаунт, что и на сайте.", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (selected != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = { selected = null; readingText = "" }) { Text("← Назад") }
            Text(selected!!.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            if (readingText.isBlank()) {
                Text("Загружаю книгу…")
                LaunchedEffect(selected!!.id) {
                    try {
                        val bookId = selected!!.id
                        readingText = api.readBook(bookId)
                        val progress = api.readingProgress(bookId)
                        readerScrollState.scrollTo(progress.optInt("scrollY", 0).coerceAtLeast(0))
                    } catch (e: Exception) { readingText = "Не удалось открыть книгу: ${e.message}" }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(readerScrollState)) {
                    Text(readingText, style = MaterialTheme.typography.bodyLarge.copy(fontSize = (16f * textScale).sp, lineHeight = (25f * textScale).sp))
                }
            }
            LaunchedEffect(selected!!.id, readingText) {
                snapshotFlow { readerScrollState.value }
                    .drop(1)
                    .debounce(900)
                    .collect { position ->
                        if (readingText.isNotBlank()) {
                            try { api.saveReadingProgress(selected!!.id, JSONObject().put("scrollY", position)) }
                            catch (_: Exception) { /* Keep reading even if progress sync is temporarily unavailable. */ }
                        }
                    }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBarCompat(
                    title = if (tab == "account") "Личный кабинет" else "Библиотека",
                    onLogout = { api.logout(); session = false }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == "catalog", onClick = { tab = "catalog" }, icon = { Icon(Icons.Default.Book, null) }, label = { Text("Книги") })
                    NavigationBarItem(selected = tab == "downloads", onClick = { tab = "downloads" }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Офлайн") })
                    NavigationBarItem(selected = tab == "account", onClick = { tab = "account" }, icon = { Icon(Icons.Default.Logout, null) }, label = { Text("Аккаунт") })
                }
            }
        ) { padding ->
            when (tab) {
                "account" -> Column(Modifier.padding(padding).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ваш аккаунт BookCheef", style = MaterialTheme.typography.titleLarge)
                    Text("Размер текста", style = MaterialTheme.typography.titleMedium)
                    Text("Настройте размер для комфортного чтения: ${ (textScale * 100).toInt() }%")
                    Slider(value = textScale, onValueChange = { textScale = it; preferences.edit().putFloat("text_scale", it).apply() }, valueRange = 0.85f..1.8f)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text("Меньше") ; Text("Больше") }
                    TextButton(onClick = { textScale = 1f; preferences.edit().putFloat("text_scale", 1f).apply() }) { Text("Сбросить размер") }
                    Text("Вход выполнен: ${api.currentEmail().ifBlank { "аккаунт BookCheef" }}")
                    Text("Избранное, история чтения и оценки будут синхронизироваться с сайтом по мере подключения соответствующих разделов.")
                    OutlinedButton(onClick = { api.logout(); session = false }) { Text("Выйти") }
                }
                else -> {
                    val shown = if (tab == "downloads") books.filter { downloaded.contains(it.id) } else books
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (tab == "downloads" && shown.isEmpty()) Text("Пока нет сохранённых книг. Нажмите значок загрузки в каталоге.", modifier = Modifier.padding(20.dp))
                        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(shown, key = { it.id }) { book ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).clickable {
                                            readingText = ""
                                            selected = book
                                        }) {
                                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                                            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                                            if (book.description.isNotBlank()) Text(book.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                            Text(if (book.isFree) "Бесплатно" else "Доступ по условиям сайта", style = MaterialTheme.typography.labelSmall)
                                        }
                                        IconButton(onClick = {
                                            scope.launch {
                                                try { api.downloadBook(book.id); downloaded = api.downloadedIds() }
                                                catch (e: Exception) { error = e.message ?: "Не удалось сохранить книгу" }
                                            }
                                        }) { Icon(Icons.Default.Download, contentDescription = "Скачать для офлайн") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarCompat(title: String, onLogout: () -> Unit) {
    TopAppBar(title = { Text(title) }, actions = { IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Выйти") } })
}

data class BookItem(val id: String, val title: String, val description: String, val author: String, val isFree: Boolean)

