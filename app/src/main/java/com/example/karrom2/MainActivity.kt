package com.example.karrom2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

// ==============================
// 1. DATA LAYER (MODELS & API - GADING.DEV)
// ==============================

// Kita ganti ke API ini karena mendukung Bahasa Inggris & Indonesia sekaligus
const val BASE_URL = "https://api.quran.gading.dev/"

// --- Models ---
data class SurahResponse(val code: Int, val data: List<SurahSummary>)
data class SurahDetailResponse(val code: Int, val data: SurahDetailData)

data class SurahSummary(
    val number: Int,
    val name: SurahName,
    val revelation: Revelation
)

data class SurahDetailData(
    val number: Int,
    val name: SurahName,
    val revelation: Revelation,
    val verses: List<Verse>
)

data class SurahName(
    val short: String,
    val transliteration: TranslationBlock, // Nama Latin
    val translation: TranslationBlock // Arti Surat
)

data class Revelation(val id: String) // Mekah/Madinah

data class Verse(
    val number: VerseNumber,
    val text: VerseText,
    val translation: TranslationBlock // Terjemahan Ayat (ID & EN)
)

data class VerseNumber(val inSurah: Int)
data class VerseText(val arab: String, val transliteration: TransliterationDetail)
data class TransliterationDetail(val en: String)

// Ini kunci fitur 2 bahasa: menyimpan text ID dan EN
data class TranslationBlock(
    val en: String,
    val id: String
)

// --- Retrofit Service ---
interface QuranApi {
    @GET("surah")
    suspend fun getSurahList(): SurahResponse

    @GET("surah/{number}")
    suspend fun getSurahDetail(@Path("number") number: Int): SurahDetailResponse
}

object RetrofitClient {
    val api: QuranApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuranApi::class.java)
    }
}

// ==============================
// 2. VIEW MODEL & STATE
// ==============================

enum class AppLanguage { INDONESIA, ENGLISH }

data class UiStrings(
    val searchHint: String,
    val appTitle: String,
    val verse: String,
    val loading: String,
    val error: String,
    val copied: String,
    val surahInfo: String
)

val stringsId = UiStrings("Cari Surat...", "Karrom2 Al-Quran", "Ayat", "Memuat...", "Gagal", "Teks disalin", "Ayat")
val stringsEn = UiStrings("Search Surah...", "Karrom2 Quran", "Verse", "Loading...", "Error", "Text copied", "Verses")

class QuranViewModel : ViewModel() {
    private val api = RetrofitClient.api

    // Language State
    private val _language = MutableStateFlow(AppLanguage.INDONESIA)
    val language = _language.asStateFlow()

    val uiStrings: StateFlow<UiStrings> = _language.map {
        if (it == AppLanguage.INDONESIA) stringsId else stringsEn
    }.stateIn(viewModelScope, SharingStarted.Eagerly, stringsId)

    // Data States
    private val _surahList = MutableStateFlow<List<SurahSummary>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _detailSurah = MutableStateFlow<SurahDetailData?>(null)
    val detailSurah = _detailSurah.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Filter Logic
    val filteredSurahList = combine(_surahList, _searchQuery) { list, query ->
        if (query.isBlank()) list
        else list.filter {
            it.name.transliteration.id.contains(query, ignoreCase = true) ||
                    it.name.translation.id.contains(query, ignoreCase = true) ||
                    it.number.toString() == query
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        fetchSurahList()
    }

    fun toggleLanguage() {
        _language.value = if (_language.value == AppLanguage.INDONESIA) AppLanguage.ENGLISH else AppLanguage.INDONESIA
    }

    fun onSearch(query: String) { _searchQuery.value = query }

    private fun fetchSurahList() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = api.getSurahList()
                _surahList.value = response.data
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun fetchSurahDetail(nomor: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _detailSurah.value = null
            try {
                val response = api.getSurahDetail(nomor)
                _detailSurah.value = response.data
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
}

// ==============================
// 3. UI LAYER (COMPOSE)
// ==============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Karrom2Theme {
                KarromApp()
            }
        }
    }
}

@Composable
fun Karrom2Theme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFF81C784),
        secondary = Color(0xFFA5D6A7),
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        onPrimary = Color.Black,
        onSurface = Color.White
    ) else lightColorScheme(
        primary = Color(0xFF2E7D32),
        secondary = Color(0xFF4CAF50),
        background = Color(0xFFF5F5F5),
        surface = Color.White,
        onPrimary = Color.White,
        onSurface = Color.Black
    )

    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun KarromApp() {
    val navController = rememberNavController()
    val viewModel: QuranViewModel = viewModel()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController, viewModel)
        }
        composable(
            route = "detail/{nomor}",
            arguments = listOf(navArgument("nomor") { type = NavType.IntType })
        ) { backStackEntry ->
            val nomor = backStackEntry.arguments?.getInt("nomor") ?: 1
            DetailScreen(navController, viewModel, nomor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: androidx.navigation.NavController, viewModel: QuranViewModel) {
    val surahs by viewModel.filteredSurahList.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val currentLang by viewModel.language.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiStrings.appTitle, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.toggleLanguage() }) {
                        Icon(Icons.Default.Language, contentDescription = "Lang")
                    }
                    Text(
                        text = if (currentLang == AppLanguage.INDONESIA) "ID" else "EN",
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.onSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(uiStrings.searchHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            if (isLoading && surahs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(surahs) { surah ->
                        SurahItem(surah, currentLang) {
                            navController.navigate("detail/${surah.number}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SurahItem(surah: SurahSummary, lang: AppLanguage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = surah.number.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1.0f)) {
                Text(text = surah.name.transliteration.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // MENAMPILKAN ARTI SESUAI BAHASA
                Text(
                    text = if(lang == AppLanguage.INDONESIA) surah.name.translation.id else surah.name.translation.en,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            // Font Arab untuk Nama Surat
            Text(
                text = surah.name.short,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Serif
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(navController: androidx.navigation.NavController, viewModel: QuranViewModel, nomor: Int) {
    val surahDetail by viewModel.detailSurah.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val currentLang by viewModel.language.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(nomor) {
        viewModel.fetchSurahDetail(nomor)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = surahDetail?.name?.transliteration?.id ?: uiStrings.loading, fontWeight = FontWeight.Bold)
                        if (surahDetail != null) {
                            val meaning = if(currentLang == AppLanguage.INDONESIA) surahDetail!!.name.translation.id else surahDetail!!.name.translation.en
                            Text(
                                text = "$meaning • ${surahDetail!!.revelation.id}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                surahDetail?.let { detail ->
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        items(detail.verses) { ayat ->
                            AyatItem(ayat, uiStrings, currentLang, context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AyatItem(ayat: Verse, uiStrings: UiStrings, lang: AppLanguage, context: Context) {
    // Logic memilih terjemahan
    val translationText = if (lang == AppLanguage.INDONESIA) ayat.translation.id else ayat.translation.en

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Nomor & Copy Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${uiStrings.verse} ${ayat.number.inSurah}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Quran Verse",
                        "QS ${ayat.number.inSurah}\n${ayat.text.arab}\n\n$translationText")
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, uiStrings.copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- FITUR GANTI FONT ARAB ---
            // Menggunakan FontFamily.Serif + Ukuran Besar untuk efek kaligrafi yang lebih bagus
            Text(
                text = ayat.text.arab,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    textAlign = TextAlign.End,
                    lineHeight = 55.sp, // Jarak antar baris lebih lega
                    fontFamily = FontFamily.Serif, // Mengubah style font jadi Serif (mirip Naskh)
                    fontSize = 32.sp, // Ukuran font diperbesar
                    fontWeight = FontWeight.Normal
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transliteration (Latin)
            Text(
                text = ayat.text.transliteration.en, // API ini pakai key 'en' untuk latin
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Translation (Bisa berubah Indo/Inggris)
            Text(
                text = translationText,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp
            )
        }
    }
}