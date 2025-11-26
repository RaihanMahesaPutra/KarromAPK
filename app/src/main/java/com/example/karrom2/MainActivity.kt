package com.example.karrom2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==============================
// 1. DATA LAYER (QURAN + PRAYER API)
// ==============================

// --- QURAN MODELS (Gading Dev) ---
const val BASE_URL_QURAN = "https://api.quran.gading.dev/"

data class SurahResponse(val code: Int, val data: List<SurahSummary>)
data class SurahDetailResponse(val code: Int, val data: SurahDetailData)
data class SurahSummary(val number: Int, val name: SurahName, val revelation: Revelation)
data class SurahDetailData(val number: Int, val name: SurahName, val revelation: Revelation, val verses: List<Verse>)
data class SurahName(val short: String, val transliteration: TranslationBlock, val translation: TranslationBlock)
data class Revelation(val id: String)
data class Verse(val number: VerseNumber, val text: VerseText, val translation: TranslationBlock)
data class VerseNumber(val inSurah: Int)
data class VerseText(val arab: String, val transliteration: TransliterationDetail)
data class TransliterationDetail(val en: String)
data class TranslationBlock(val en: String, val id: String)

// --- PRAYER MODELS (Aladhan) ---
const val BASE_URL_PRAYER = "https://api.aladhan.com/v1/"

data class PrayerResponse(val code: Int, val data: PrayerData)
data class PrayerData(val timings: Timings, val meta: Meta)
data class Timings(
    val Fajr: String, val Sunrise: String, val Dhuhr: String,
    val Asr: String, val Maghrib: String, val Isha: String, val Imsak: String
)
data class Meta(val method: Method, val timezone: String)
data class Method(val name: String) // Nama metode perhitungan (Kemenag, dll)

// --- RETROFIT INTERFACES ---
interface QuranApi {
    @GET("surah") suspend fun getSurahList(): SurahResponse
    @GET("surah/{number}") suspend fun getSurahDetail(@Path("number") number: Int): SurahDetailResponse
}

interface PrayerApi {
    // Method 20 = Kementerian Agama RI
    @GET("timings/{date}")
    suspend fun getTimings(
        @Path("date") date: String,
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("method") method: Int = 20
    ): PrayerResponse
}

object ApiClient {
    val quranApi: QuranApi by lazy {
        Retrofit.Builder().baseUrl(BASE_URL_QURAN)
            .addConverterFactory(GsonConverterFactory.create()).build().create(QuranApi::class.java)
    }
    val prayerApi: PrayerApi by lazy {
        Retrofit.Builder().baseUrl(BASE_URL_PRAYER)
            .addConverterFactory(GsonConverterFactory.create()).build().create(PrayerApi::class.java)
    }
}

// ==============================
// 2. VIEW MODEL
// ==============================

enum class AppLanguage { INDONESIA, ENGLISH }

data class UiStrings(
    val searchHint: String, val appTitle: String, val verse: String,
    val loading: String, val error: String, val copied: String,
    val tabQuran: String, val tabPrayer: String, val locateTitle: String,
    val locateButton: String, val imsak: String, val subuh: String,
    val terbit: String, val dzuhur: String, val ashar: String,
    val maghrib: String, val isya: String, val locationFound: String
)

val stringsId = UiStrings(
    "Cari Surat...", "Karrom2", "Ayat", "Memuat...", "Gagal", "Teks disalin",
    "Al-Quran", "Jadwal Sholat", "Lokasi Anda", "Cari Lokasi Saya",
    "Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya", "Lokasi ditemukan"
)
val stringsEn = UiStrings(
    "Search Surah...", "Karrom2", "Verse", "Loading...", "Error", "Text copied",
    "Quran", "Prayer Times", "Your Location", "Get My Location",
    "Imsak", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha", "Location found"
)

class MainViewModel : ViewModel() {
    // --- LANGUAGE & UI ---
    private val _language = MutableStateFlow(AppLanguage.INDONESIA)
    val language = _language.asStateFlow()
    val uiStrings = _language.map { if (it == AppLanguage.INDONESIA) stringsId else stringsEn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, stringsId)

    fun toggleLanguage() {
        _language.value = if (_language.value == AppLanguage.INDONESIA) AppLanguage.ENGLISH else AppLanguage.INDONESIA
    }

    // --- QURAN STATE ---
    private val _surahList = MutableStateFlow<List<SurahSummary>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _detailSurah = MutableStateFlow<SurahDetailData?>(null)
    val detailSurah = _detailSurah.asStateFlow()
    private val _isLoadingQuran = MutableStateFlow(false)
    val isLoadingQuran = _isLoadingQuran.asStateFlow()

    val filteredSurahList = combine(_surahList, _searchQuery) { list, query ->
        if (query.isBlank()) list else list.filter {
            it.name.transliteration.id.contains(query, true) || it.name.translation.id.contains(query, true) || it.number.toString() == query
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- PRAYER STATE ---
    private val _prayerTimes = MutableStateFlow<Timings?>(null)
    val prayerTimes = _prayerTimes.asStateFlow()
    private val _locationName = MutableStateFlow("Menunggu Lokasi...")
    val locationName = _locationName.asStateFlow()
    private val _isLoadingPrayer = MutableStateFlow(false)
    val isLoadingPrayer = _isLoadingPrayer.asStateFlow()

    init { fetchSurahList() }

    fun onSearch(query: String) { _searchQuery.value = query }

    private fun fetchSurahList() {
        viewModelScope.launch {
            _isLoadingQuran.value = true
            try { _surahList.value = ApiClient.quranApi.getSurahList().data }
            catch (e: Exception) { e.printStackTrace() }
            finally { _isLoadingQuran.value = false }
        }
    }

    fun fetchSurahDetail(nomor: Int) {
        viewModelScope.launch {
            _isLoadingQuran.value = true
            _detailSurah.value = null
            try { _detailSurah.value = ApiClient.quranApi.getSurahDetail(nomor).data }
            catch (e: Exception) { e.printStackTrace() }
            finally { _isLoadingQuran.value = false }
        }
    }

    fun fetchPrayerTimes(lat: Double, long: Double) {
        viewModelScope.launch {
            _isLoadingPrayer.value = true
            try {
                // Get Date dd-MM-yyyy
                val date = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
                val response = ApiClient.prayerApi.getTimings(date, lat, long)
                _prayerTimes.value = response.data.timings
                _locationName.value = "Lat: ${String.format("%.4f", lat)}, Long: ${String.format("%.4f", long)}"
            } catch (e: Exception) {
                _locationName.value = "Gagal mengambil jadwal"
                e.printStackTrace()
            } finally {
                _isLoadingPrayer.value = false
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
                MainApp()
            }
        }
    }
}

@Composable
fun Karrom2Theme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7),
        background = Color(0xFF121212), surface = Color(0xFF1E1E1E),
        onPrimary = Color.Black, onSurface = Color.White
    ) else lightColorScheme(
        primary = Color(0xFF2E7D32), secondary = Color(0xFF4CAF50),
        background = Color(0xFFF5F5F5), surface = Color.White,
        onPrimary = Color.White, onSurface = Color.Black
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// --- NAVIGATION STRUCTURE ---
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Quran : Screen("quran", "Al-Quran", Icons.Default.MenuBook)
    object Prayer : Screen("prayer", "Sholat", Icons.Default.AccessTime)
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val uiStrings by viewModel.uiStrings.collectAsState()

    // Bottom Nav Items
    val items = listOf(Screen.Quran, Screen.Prayer)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(if(screen is Screen.Quran) uiStrings.tabQuran else uiStrings.tabPrayer) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Quran.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Quran.route) { QuranScreen(navController, viewModel) }
            composable(Screen.Prayer.route) { PrayerScreen(viewModel) }
            // Detail Quran Screen (Hidden from BottomBar)
            composable(
                route = "detail/{nomor}",
                arguments = listOf(navArgument("nomor") { type = NavType.IntType })
            ) { backStackEntry ->
                val nomor = backStackEntry.arguments?.getInt("nomor") ?: 1
                DetailScreen(navController, viewModel, nomor)
            }
        }
    }
}

// --- SCREEN 1: QURAN LIST ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranScreen(navController: androidx.navigation.NavController, viewModel: MainViewModel) {
    val surahs by viewModel.filteredSurahList.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoadingQuran.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val currentLang by viewModel.language.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar Manual implementation to include Search & Language
        Surface(color = MaterialTheme.colorScheme.primary, shadowElevation = 4.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(uiStrings.appTitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { viewModel.toggleLanguage() }) {
                        Icon(Icons.Default.Language, contentDescription = "Lang", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { viewModel.onSearch(it) },
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    placeholder = { Text(uiStrings.searchHint) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp), singleLine = true
                )
            }
        }

        if (isLoading && surahs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(surahs) { surah ->
                    SurahItem(surah, currentLang) { navController.navigate("detail/${surah.number}") }
                }
            }
        }
    }
}

@Composable
fun SurahItem(surah: SurahSummary, lang: AppLanguage, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = surah.number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1.0f)) {
                Text(text = surah.name.transliteration.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if(lang == AppLanguage.INDONESIA) surah.name.translation.id else surah.name.translation.en,
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
            }
            Text(text = surah.name.short, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- SCREEN 2: PRAYER TIMES (JADWAL SHOLAT) ---
@Composable
fun PrayerScreen(viewModel: MainViewModel) {
    val prayerTimes by viewModel.prayerTimes.collectAsState()
    val locationName by viewModel.locationName.collectAsState()
    val isLoading by viewModel.isLoadingPrayer.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Permission Launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            getCurrentLocation(context, fusedLocationClient, viewModel)
        } else {
            Toast.makeText(context, "Izin lokasi diperlukan", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = uiStrings.tabPrayer, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))

        // Location Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = locationName, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        getCurrentLocation(context, fusedLocationClient, viewModel)
                    }
                }) {
                    Text(uiStrings.locateButton)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            prayerTimes?.let { times ->
                PrayerItem(uiStrings.imsak, times.Imsak)
                PrayerItem(uiStrings.subuh, times.Fajr)
                PrayerItem(uiStrings.terbit, times.Sunrise) // Opsional
                PrayerItem(uiStrings.dzuhur, times.Dhuhr)
                PrayerItem(uiStrings.ashar, times.Asr)
                PrayerItem(uiStrings.maghrib, times.Maghrib, isHighlight = true)
                PrayerItem(uiStrings.isya, times.Isha)

                Spacer(modifier = Modifier.height(16.dp))
                Text("Metode: Kemenag RI", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            } ?: Text("Silakan cari lokasi Anda untuk melihat jadwal", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
    }
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(context: Context, fusedLocationClient: FusedLocationProviderClient, viewModel: MainViewModel) {
    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
        if (location != null) {
            viewModel.fetchPrayerTimes(location.latitude, location.longitude)
        } else {
            // Request update jika lastLocation null
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let {
                        viewModel.fetchPrayerTimes(it.latitude, it.longitude)
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }.addOnFailureListener {
        Toast.makeText(context, "Gagal mendapatkan lokasi", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PrayerItem(name: String, time: String, isHighlight: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if(isHighlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = if(isHighlight) FontWeight.Bold else FontWeight.Normal)
            Text(text = time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// --- SCREEN 3: DETAIL QURAN (Sama seperti sebelumnya) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(navController: androidx.navigation.NavController, viewModel: MainViewModel, nomor: Int) {
    val surahDetail by viewModel.detailSurah.collectAsState()
    val isLoading by viewModel.isLoadingQuran.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val currentLang by viewModel.language.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(nomor) { viewModel.fetchSurahDetail(nomor) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = surahDetail?.name?.transliteration?.id ?: uiStrings.loading, fontWeight = FontWeight.Bold)
                        if (surahDetail != null) {
                            val meaning = if(currentLang == AppLanguage.INDONESIA) surahDetail!!.name.translation.id else surahDetail!!.name.translation.en
                            Text(text = "$meaning • ${surahDetail!!.revelation.id}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
            else {
                surahDetail?.let { detail ->
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        items(detail.verses) { ayat -> AyatItem(ayat, uiStrings, currentLang, context) }
                    }
                }
            }
        }
    }
}

@Composable
fun AyatItem(ayat: Verse, uiStrings: UiStrings, lang: AppLanguage, context: Context) {
    val translationText = if (lang == AppLanguage.INDONESIA) ayat.translation.id else ayat.translation.en
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(text = "${uiStrings.verse} ${ayat.number.inSurah}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Quran Verse", "QS ${ayat.number.inSurah}\n${ayat.text.arab}\n\n$translationText")
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, uiStrings.copied, Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray, modifier = Modifier.size(20.dp)) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = ayat.text.arab, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End, lineHeight = 55.sp, fontFamily = FontFamily.Serif, fontSize = 32.sp, fontWeight = FontWeight.Normal))
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = ayat.text.transliteration.en, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = translationText, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
        }
    }
}