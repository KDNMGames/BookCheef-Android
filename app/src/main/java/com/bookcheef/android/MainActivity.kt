package com.bookcheef.android

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Ink = Color(0xFF100E16)
private val Panel = Color(0xFF1B1722)
private val PanelLight = Color(0xFF26202D)
private val Gold = Color(0xFFC6A36A)
private val GoldLight = Color(0xFFF1D79E)
private val Muted = Color(0xFFB7AD9F)
private val Cream = Color(0xFFF4F0E8)
private val Plum = Color(0xFF49365D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 14, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 14, 22)
        setContent { BookCheefTheme { BookCheefApp() } }
    }
}

@Composable
private fun BookCheefTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            onPrimary = Ink,
            secondary = Plum,
            background = Ink,
            surface = Panel,
            onSurface = Cream,
            onBackground = Cream,
            error = Color(0xFFFF8B91)
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}

@OptIn(FlowPreview::class)
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
    var loadingReader by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    var selected by remember { mutableStateOf<BookItem?>(null) }
    var readingText by remember { mutableStateOf("") }
    var readingFileUrl by remember { mutableStateOf<String?>(null) }
    var readingFileName by remember { mutableStateOf("") }
    val readerScrollState = rememberScrollState()
    var downloaded by remember { mutableStateOf(setOf<String>()) }
    var tab by remember { mutableStateOf("catalog") }
    var search by remember { mutableStateOf("") }
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
            }
            busy = false
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
            onToggleMode = { register = !register; error = "" },
            busy = busy,
            error = error,
            onSubmit = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        api.authenticate(email.trim(), password, register)
                        session = api.hasSession()
                        if (!session && register) error = "Аккаунт создан. Подтвердите почту, затем войдите."
                    } catch (e: Exception) {
                        error = e.message ?: "Ошибка авторизации"
                    }
                    busy = false
                }
            }
        )
    } else if (selected != null) {
        ReaderScreen(
            book = selected!!,
            text = readingText,
            externalUrl = readingFileUrl,
            fileName = readingFileName,
            onOpenFile = { url ->
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { error = "Не удалось открыть файл: ${e.message}" }
            },
            loading = loadingReader,
            scrollState = readerScrollState,
            textScale = textScale,
            onBack = {
                val bookId = selected!!.id
                scope.launch {
                    try { api.saveReadingProgress(bookId, JSONObject().put("scrollY", readerScrollState.value)) } catch (_: Exception) {}
                    selected = null
                    readingText = ""
                    loadingReader = false
                }
            }
        )
        LaunchedEffect(selected?.id) {
            val book = selected ?: return@LaunchedEffect
            readingText = ""
            readingFileUrl = null
            readingFileName = ""
            loadingReader = true
            error = ""
            try {
                val result = api.readBook(book.id, book.isFree)
                readingText = result.text
                readingFileUrl = result.externalUrl
                readingFileName = result.fileName
                // Progress sync is optional: an unapplied history migration must not block reading.
                val progress = try { api.readingProgress(book.id) } catch (_: Exception) { JSONObject() }
                withFrameNanosCompat()
                withFrameNanosCompat()
                readerScrollState.scrollTo(progress.optInt("scrollY", 0).coerceAtLeast(0))
            } catch (e: Exception) {
                readingText = "Не удалось открыть книгу.\n\n${e.message ?: "Проверьте подключение и доступ к книге."}"
            } finally {
                loadingReader = false
            }
        }
        LaunchedEffect(selected?.id, readingText) {
            snapshotFlow { readerScrollState.value }
                .drop(1)
                .debounce(900)
                .collect { position ->
                    val bookId = selected?.id ?: return@collect
                    if (readingText.isNotBlank()) {
                        try { api.saveReadingProgress(bookId, JSONObject().put("scrollY", position)) } catch (_: Exception) {}
                    }
                }
        }
    } else {
        Scaffold(
            containerColor = Ink,
            topBar = {
                BookCheefHeader(
                    title = when (tab) {
                        "account" -> "Личный кабинет"
                        "downloads" -> "Моя библиотека"
                        else -> "Книжная галерея"
                    },
                    onLogout = { api.logout(); session = false; selected = null }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Cream) {
                    NavigationBarItem(
                        selected = tab == "catalog",
                        onClick = { tab = "catalog" },
                        icon = { Icon(Icons.Default.Book, null) },
                        label = { Text("Каталог") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = GoldLight, selectedTextColor = GoldLight, indicatorColor = Plum, unselectedIconColor = Muted, unselectedTextColor = Muted)
                    )
                    NavigationBarItem(
                        selected = tab == "downloads",
                        onClick = { tab = "downloads" },
                        icon = { Icon(Icons.Default.Download, null) },
                        label = { Text("Офлайн") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = GoldLight, selectedTextColor = GoldLight, indicatorColor = Plum, unselectedIconColor = Muted, unselectedTextColor = Muted)
                    )
                    NavigationBarItem(
                        selected = tab == "account",
                        onClick = { tab = "account" },
                        icon = { Icon(Icons.Default.Person, null) },
                        label = { Text("Аккаунт") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = GoldLight, selectedTextColor = GoldLight, indicatorColor = Plum, unselectedIconColor = Muted, unselectedTextColor = Muted)
                    )
                }
            }
        ) { padding ->
            when (tab) {
                "account" -> AccountScreen(
                    email = api.currentEmail(),
                    textScale = textScale,
                    onTextScale = { textScale = it; preferences.edit().putFloat("text_scale", it).apply() },
                    onReset = { textScale = 1f; preferences.edit().putFloat("text_scale", 1f).apply() },
                    onLogout = { api.logout(); session = false }
                )
                else -> {
                    val shown = if (tab == "downloads") books.filter { downloaded.contains(it.id) } else books
                    val filtered = shown.filter {
                        search.isBlank() || listOf(it.title, it.author, it.description, it.genre, it.series).joinToString(" ").contains(search, ignoreCase = true)
                    }
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (tab == "catalog") {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Text("Истории, которые остаются с тобой", color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(5.dp))
                                Text("Открой для себя новые миры среди страниц любимых книг.", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(15.dp))
                                OutlinedTextField(
                                    value = search,
                                    onValueChange = { search = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    placeholder = { Text("Найти книгу или автора…", color = Muted) },
                                    leadingIcon = { Icon(Icons.Default.MenuBook, null, tint = Gold) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Gold,
                                        unfocusedBorderColor = Gold.copy(alpha = .35f),
                                        focusedTextColor = Cream,
                                        unfocusedTextColor = Cream,
                                        cursorColor = Gold
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            }
                        }
                        if (error.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2027)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9E4B58))
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(error, color = Color(0xFFFFC3C7), fontSize = 13.sp)
                                    TextButton(onClick = { loadCatalog() }) { Icon(Icons.Default.Refresh, null); Text("Повторить загрузку") }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (tab == "downloads") "Сохранённые книги" else "Все книги", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${filtered.size}", color = Muted, fontSize = 12.sp)
                            IconButton(onClick = { loadCatalog() }) { Icon(Icons.Default.Refresh, "Обновить", tint = Gold) }
                        }
                        if (busy && books.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Gold)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Загружаем библиотеку…", color = Muted)
                                }
                            }
                        } else if (filtered.isEmpty()) {
                            EmptyCatalog(
                                title = if (tab == "downloads") "Здесь пока пусто" else "Книги пока не найдены",
                                subtitle = if (tab == "downloads") "Сохраняйте бесплатные книги, чтобы читать их без интернета." else "Проверьте подключение и обновите каталог."
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(filtered, key = { it.id }) { book ->
                                    BookCard(
                                        book = book,
                                        downloaded = downloaded.contains(book.id),
                                        onOpen = { readingText = ""; selected = book },
                                        onDownload = {
                                            scope.launch {
                                                try {
                                                    api.downloadBook(book.id, book.isFree)
                                                    downloaded = api.downloadedIds()
                                                } catch (e: Exception) {
                                                    error = e.message ?: "Не удалось сохранить книгу"
                                                }
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
}

@Composable
private fun AuthScreen(
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    register: Boolean,
    onToggleMode: () -> Unit,
    busy: Boolean,
    error: String,
    onSubmit: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF292033), Ink, Color(0xFF0C0B11)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(26.dp))
                .background(Panel).border(1.dp, Gold.copy(alpha = .48f), RoundedCornerShape(26.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(68.dp).clip(RoundedCornerShape(20.dp)).background(Gold.copy(alpha = .14f))
                    .border(1.dp, Gold.copy(alpha = .5f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.MenuBook, null, tint = GoldLight, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(15.dp))
            Text("BookCheef", color = GoldLight, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Книжная галерея", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(25.dp))
            Text(if (register) "Создать аккаунт" else "С возвращением!", color = Cream, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(if (register) "Зарегистрируйтесь, чтобы открыть свою библиотеку" else "Войдите, чтобы продолжить чтение", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = email, onValueChange = onEmail, modifier = Modifier.fillMaxWidth(),
                label = { Text("Электронная почта") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = authFieldColors(), shape = RoundedCornerShape(13.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
                label = { Text("Пароль") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = authFieldColors(), shape = RoundedCornerShape(13.dp)
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = Color(0xFFFFA8AE), fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !busy && email.isNotBlank() && password.length >= 6,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink, disabledContainerColor = Gold.copy(alpha = .35f))
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Ink, strokeWidth = 2.dp)
                else Text(if (register) "ЗАРЕГИСТРИРОВАТЬСЯ" else "ВОЙТИ", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onToggleMode) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться", color = GoldLight, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Muted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text("Один аккаунт для сайта и приложения", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = Gold.copy(alpha = .32f),
    focusedLabelColor = GoldLight,
    unfocusedLabelColor = Muted,
    focusedTextColor = Cream,
    unfocusedTextColor = Cream,
    cursorColor = Gold
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookCheefHeader(title: String, onLogout: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Gold.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MenuBook, null, tint = GoldLight)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("BookCheef", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(title, color = Muted, fontSize = 10.sp)
                }
            }
        },
        actions = { IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Выйти", tint = Muted) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink, titleContentColor = Cream)
    )
}

@Composable
private fun BookCard(book: BookItem, downloaded: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .27f))
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(106.dp).height(148.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Plum, PanelLight)))
            ) {
                SubcomposeAsyncImage(
                    model = book.coverUrl,
                    contentDescription = "Обложка: ${book.title}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = { CoverPlaceholder(book.title) },
                    error = { CoverPlaceholder(book.title) }
                )
                if (book.isInteractive) {
                    Box(Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Plum).padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text("ИНТЕРАКТИВ", color = Cream, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(book.author, color = GoldLight, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                if (book.description.isNotBlank()) Text(book.description, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val metadata = listOf(book.age, book.genre, book.series).filter { it.isNotBlank() }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(metadata, color = Muted.copy(alpha = .8f), fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).background(if (book.isFree) Color(0xFF254436) else Color(0xFF49371F)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                        Text(if (book.isFree) "БЕСПЛАТНО" else "PRO", color = if (book.isFree) Color(0xFFA9E2BF) else GoldLight, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (book.isFree) {
                        IconButton(onClick = onDownload, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Download, if (downloaded) "Сохранено" else "Скачать", tint = if (downloaded) GoldLight else Muted, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Plum, PanelLight))).padding(8.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.MenuBook, null, tint = Gold, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, color = Cream, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReaderScreen(
    book: BookItem,
    text: String,
    externalUrl: String?,
    fileName: String,
    onOpenFile: (String) -> Unit,
    loading: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    textScale: Float,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад", tint = GoldLight) }
            Text(book.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(12.dp))
                    Text("Открываем книгу…", color = Muted)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 22.dp, vertical = 22.dp)) {
                Text(book.title, color = GoldLight, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                if (book.author.isNotBlank()) Text(book.author, color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                if (!externalUrl.isNullOrBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = PanelLight), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .4f))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.MenuBook, null, tint = GoldLight, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(fileName.ifBlank { "Файл книги" }, color = Cream, fontWeight = FontWeight.SemiBold)
                            Text("Документ защищён и будет открыт по временной ссылке.", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { onOpenFile(externalUrl) }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Открыть книгу") }
                        }
                    }
                } else {
                    Text(text.ifBlank { "В этой книге пока нет текста." }, color = Cream, fontSize = (17f * textScale).sp, lineHeight = (28f * textScale).sp)
                }
                Spacer(Modifier.height(70.dp))
            }
        }
    }
}

@Composable
private fun AccountScreen(
    email: String,
    textScale: Float,
    onTextScale: (Float) -> Unit,
    onReset: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .3f))) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Gold.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, tint = GoldLight, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text("Ваш аккаунт BookCheef", color = GoldLight, fontWeight = FontWeight.SemiBold)
                    Text(email.ifBlank { "Аккаунт BookCheef" }, color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("Настройки чтения", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("Размер текста", color = Cream, fontWeight = FontWeight.Medium)
                Text("${(textScale * 100).toInt()}%", color = GoldLight, fontSize = 13.sp)
                Slider(value = textScale, onValueChange = onTextScale, valueRange = .85f..1.8f, colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Меньше", color = Muted, fontSize = 11.sp)
                    Text("Больше", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = onReset) { Text("Сбросить размер", color = GoldLight) }
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .5f))) {
            Icon(Icons.Default.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Выйти из аккаунта")
        }
    }
}

@Composable
private fun EmptyCatalog(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.MenuBook, null, tint = Gold.copy(alpha = .75f), modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

private suspend fun withFrameNanosCompat() {
    kotlinx.coroutines.yield()
}
