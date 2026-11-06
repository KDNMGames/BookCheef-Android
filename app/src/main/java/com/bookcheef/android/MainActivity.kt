package com.bookcheef.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Ink = Color(0xFF09090D)
private val Panel = Color(0xFF17151D)
private val PanelLight = Color(0xFF211D29)
private val Gold = Color(0xFFC6A36A)
private val GoldLight = Color(0xFFE7C17B)
private val Muted = Color(0xFFB9B2A8)
private val Cream = Color(0xFFF4F0E8)

private val BookCheefColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF17120B),
    secondary = GoldLight,
    background = Ink,
    surface = Panel,
    onSurface = Cream,
    onBackground = Cream,
    error = Color(0xFFFF9A9A)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 13)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 13)
        setContent { MaterialTheme(colorScheme = BookCheefColors) { BookCheefApp() } }
    }
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
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
    var readingLoading by remember { mutableStateOf(false) }
    val readerScrollState = rememberScrollState()
    var downloaded by remember { mutableStateOf(setOf<String>()) }
    var tab by remember { mutableStateOf("catalog") }
    val preferences = remember { context.getSharedPreferences("bookcheef_preferences", android.content.Context.MODE_PRIVATE) }
    var textScale by remember { mutableFloatStateOf(preferences.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.8f)) }

    fun loadCatalog() {
        scope.launch {
            busy = true
            error = ""
            try {
                books = api.catalog()
                downloaded = api.downloadedIds()
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить каталог"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(session) { if (session) loadCatalog() }

    if (!session) {
        AuthScreen(
            email = email,
            onEmail = { email = it },
            password = password,
            onPassword = { password = it },
            register = register,
            busy = busy,
            error = error,
            onSubmit = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        api.authenticate(email.trim(), password, register)
                        session = api.hasSession()
                    } catch (e: Exception) {
                        error = e.message ?: "Ошибка авторизации"
                    } finally {
                        busy = false
                    }
                }
            },
            onToggle = { register = !register; error = "" }
        )
    } else if (selected != null) {
        val book = selected!!
        LaunchedEffect(book.id) {
            readingText = ""
            readingLoading = true
            try {
                val content = api.readBook(book.id, book.isFree)
                readingText = content
                val progress = api.readingProgress(book.id)
                withFrameNanos { }
                withFrameNanos { }
                readerScrollState.scrollTo(progress.optInt("scrollY", 0).coerceAtLeast(0))
            } catch (e: Exception) {
                readingText = "Не удалось открыть книгу.\n\n${e.message ?: "Проверьте подключение и доступ к книге."}"
            } finally {
                readingLoading = false
            }
        }
        LaunchedEffect(book.id, readingText) {
            snapshotFlow { readerScrollState.value }
                .drop(1)
                .debounce(900)
                .collect { position ->
                    if (readingText.isNotBlank() && !readingLoading) {
                        try { api.saveReadingProgress(book.id, JSONObject().put("scrollY", position)) }
                        catch (_: Exception) { }
                    }
                }
        }
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PanelLight, Ink)))
        ) {
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    scope.launch {
                        try { api.saveReadingProgress(book.id, JSONObject().put("scrollY", readerScrollState.value)) } catch (_: Exception) {}
                        selected = null
                        readingText = ""
                    }
                }) { Icon(Icons.Default.ArrowBack, "Назад", tint = GoldLight) }
                Column(Modifier.weight(1f)) {
                    Text("BOOKCHEEF", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(book.title, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (readingLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold, trackColor = PanelLight)
                Text("Открываем книгу…", color = Muted, modifier = Modifier.padding(20.dp))
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(readerScrollState).padding(horizontal = 20.dp, vertical = 22.dp)
                ) {
                    Text(book.title, color = GoldLight, fontSize = 27.sp, lineHeight = 34.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    if (book.author.isNotBlank()) Text(book.author, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                    HorizontalDivider(color = Gold.copy(alpha = .35f))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        readingText,
                        color = Cream,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = (17f * textScale).sp, lineHeight = (28f * textScale).sp)
                    )
                }
            }
        }
    } else {
        Scaffold(
            containerColor = Ink,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("BOOKCHEEF", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text(if (tab == "account") "Личный кабинет" else if (tab == "downloads") "Мои загрузки" else "Книжная галерея",
                                color = Cream, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                        }
                    },
                    actions = {
                        IconButton(onClick = { loadCatalog() }) { Icon(Icons.Default.Refresh, "Обновить", tint = GoldLight) }
                        IconButton(onClick = { api.logout(); session = false }) { Icon(Icons.Default.Logout, "Выйти", tint = Muted) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Cream) {
                    NavigationBarItem(selected = tab == "catalog", onClick = { tab = "catalog" }, icon = { Icon(Icons.Default.Book, null) }, label = { Text("Книги") }, colors = navColors())
                    NavigationBarItem(selected = tab == "downloads", onClick = { tab = "downloads" }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Офлайн") }, colors = navColors())
                    NavigationBarItem(selected = tab == "account", onClick = { tab = "account" }, icon = { Icon(Icons.Default.Bookmark, null) }, label = { Text("Аккаунт") }, colors = navColors())
                }
            }
        ) { padding ->
            when (tab) {
                "account" -> Column(
                    Modifier.padding(padding).fillMaxSize().background(Ink).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Ваш аккаунт BookCheef", color = GoldLight, fontSize = 24.sp, fontFamily = FontFamily.Serif)
                    Text("Вход выполнен: ${api.currentEmail().ifBlank { "аккаунт BookCheef" }}", color = Muted)
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .25f))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Размер текста", color = Cream, fontWeight = FontWeight.SemiBold)
                            Text("Настройте чтение: ${(textScale * 100).toInt()}%", color = Muted, fontSize = 12.sp)
                            Slider(value = textScale, onValueChange = { textScale = it; preferences.edit().putFloat("text_scale", it).apply() }, valueRange = 0.85f..1.8f)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Меньше", color = Muted, fontSize = 11.sp); Text("Больше", color = Muted, fontSize = 11.sp) }
                            TextButton(onClick = { textScale = 1f; preferences.edit().putFloat("text_scale", 1f).apply() }) { Text("Сбросить размер", color = GoldLight) }
                        }
                    }
                    Text("Избранное, история чтения и оценки синхронизируются с аккаунтом сайта в поддерживаемых разделах.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    OutlinedButton(onClick = { api.logout(); session = false }, border = BorderStroke(1.dp, Gold.copy(alpha = .55f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight)) { Text("Выйти из аккаунта") }
                }
                else -> {
                    val shown = if (tab == "downloads") books.filter { downloaded.contains(it.id) } else books
                    Column(Modifier.padding(padding).fillMaxSize().background(Ink)) {
                        if (tab == "catalog") {
                            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(PanelLight, Panel))).padding(horizontal = 20.dp, vertical = 18.dp)) {
                                Text("ВАША СЛЕДУЮЩАЯ ИСТОРИЯ", color = Gold, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                                Text("Откройте мир книг", color = Cream, fontSize = 26.sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(top = 7.dp))
                                Text("Читайте любимые истории на любом устройстве.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                        if (error.isNotBlank()) {
                            Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1B20)), border = BorderStroke(1.dp, Color(0xFF9D5555))) {
                                Text(error, color = Color(0xFFFFB5B5), modifier = Modifier.padding(14.dp), fontSize = 12.sp)
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold, trackColor = Panel)
                        if (!busy && error.isBlank() && shown.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.AutoStories, null, tint = Gold, modifier = Modifier.size(44.dp))
                                Text(if (tab == "downloads") "Пока нет офлайн-книг" else "Каталог пока пуст", color = Cream, fontSize = 19.sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(top = 12.dp))
                                Text(if (tab == "downloads") "Сохраняйте бесплатные книги, чтобы читать без интернета." else "Потяните обновление или попробуйте позже.", color = Muted, fontSize = 12.sp)
                                if (tab == "catalog") TextButton(onClick = { loadCatalog() }) { Text("Обновить каталог", color = GoldLight) }
                            }
                        }
                        LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shown, key = { it.id }) { book ->
                                BookCatalogCard(
                                    book = book,
                                    downloaded = downloaded.contains(book.id),
                                    onOpen = { selected = book; readingText = ""; scope.launch { readerScrollState.scrollTo(0) } },
                                    onDownload = {
                                        scope.launch {
                                            try { api.downloadBook(book.id, book.isFree); downloaded = api.downloadedIds() }
                                            catch (e: Exception) { error = e.message ?: "Не удалось сохранить книгу" }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = GoldLight,
    selectedTextColor = GoldLight,
    indicatorColor = Gold.copy(alpha = .18f),
    unselectedIconColor = Muted,
    unselectedTextColor = Muted
)

@Composable
private fun AuthScreen(
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    register: Boolean,
    busy: Boolean,
    error: String,
    onSubmit: () -> Unit,
    onToggle: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PanelLight, Ink, Ink)))) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Gold.copy(alpha = .13f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.AutoStories, null, tint = GoldLight, modifier = Modifier.size(38.dp)) }
            Text("BOOKCHEEF", color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, modifier = Modifier.padding(top = 17.dp))
            Text("Истории, которые остаются", color = Cream, fontSize = 25.sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, Gold.copy(alpha = .32f))
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text(if (register) "Создать аккаунт" else "С возвращением", color = Cream, fontSize = 23.sp, fontFamily = FontFamily.Serif)
                    Text(if (register) "Зарегистрируйтесь, чтобы читать и сохранять книги." else "Войдите, чтобы продолжить чтение и открыть свою библиотеку.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = email, onValueChange = onEmail, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Электронная почта") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = authFieldColors()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = authFieldColors()
                    )
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFFA4A4), fontSize = 12.sp, lineHeight = 17.sp)
                    Button(
                        onClick = onSubmit, modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !busy && email.isNotBlank() && password.length >= 6,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF17120B), disabledContainerColor = Gold.copy(alpha = .4f))
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(19.dp), color = Color(0xFF17120B), strokeWidth = 2.dp)
                        else Text(if (register) "Зарегистрироваться" else "Войти", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onToggle, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться", color = GoldLight, fontSize = 12.sp)
                    }
                }
            }
            Text("Используйте тот же аккаунт, что и на сайте BookCheef.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = Color.White.copy(alpha = .18f),
    focusedLabelColor = GoldLight,
    unfocusedLabelColor = Muted,
    cursorColor = GoldLight,
    focusedTextColor = Cream,
    unfocusedTextColor = Cream
)

@Composable
private fun BookCatalogCard(book: BookItem, downloaded: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Gold.copy(alpha = .22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(104.dp).height(148.dp).clip(RoundedCornerShape(7.dp)).background(PanelLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoStories, null, tint = Gold.copy(alpha = .7f), modifier = Modifier.size(34.dp))
                if (!book.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "Обложка: ${book.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, color = GoldLight, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.description.isNotBlank()) Text(book.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOf(book.age, book.genre, book.series).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Muted.copy(alpha = .75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = if (book.isFree) Color(0xFF24372E) else Color(0xFF3B2D1D), shape = RoundedCornerShape(30.dp)) {
                        Text(if (book.isFree) "БЕСПЛАТНО" else "PRO", color = if (book.isFree) Color(0xFF9BD5B0) else GoldLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (book.isFree) {
                        IconButton(onClick = onDownload, modifier = Modifier.size(34.dp)) {
                            Icon(if (downloaded) Icons.Default.Bookmark else Icons.Default.Download, if (downloaded) "Сохранено" else "Скачать офлайн", tint = GoldLight, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}
