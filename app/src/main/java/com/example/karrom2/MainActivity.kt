package com.example.karrom2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
// Import untuk memperbaiki nativeCanvas (agar bisa drawText)
import androidx.compose.ui.graphics.nativeCanvas
// Import untuk memperbaiki @Path (agar API Retrofit jalan)
import retrofit2.http.Path
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items

// ==============================
// 1. DATA LAYER
// ==============================

const val BASE_URL_QURAN = "https://api.quran.gading.dev/"
const val BASE_URL_PRAYER = "https://api.aladhan.com/v1/"

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

data class PrayerResponse(val code: Int, val data: PrayerData)
data class PrayerData(val timings: Timings)
data class Timings(
    val Fajr: String, val Sunrise: String, val Dhuhr: String,
    val Asr: String, val Maghrib: String, val Isha: String, val Imsak: String
)

interface QuranApi {
    @GET("surah") suspend fun getSurahList(): SurahResponse
    @GET("surah/{number}") suspend fun getSurahDetail(@Path("number") number: Int): SurahDetailResponse
}
interface PrayerApi {
    @GET("timings/{date}") suspend fun getTimings(
        @Path("date") date: String, @Query("latitude") lat: Double, @Query("longitude") long: Double, @Query("method") method: Int = 20
    ): PrayerResponse
}
object ApiClient {
    val quranApi: QuranApi by lazy { Retrofit.Builder().baseUrl(BASE_URL_QURAN).addConverterFactory(GsonConverterFactory.create()).build().create(QuranApi::class.java) }
    val prayerApi: PrayerApi by lazy { Retrofit.Builder().baseUrl(BASE_URL_PRAYER).addConverterFactory(GsonConverterFactory.create()).build().create(PrayerApi::class.java) }
}

// ==============================
// 2. VIEW MODEL
// ==============================

enum class AppLanguage { INDONESIA, ENGLISH }

data class UiStrings(
    val searchHint: String, val appTitle: String, val verse: String,
    val loading: String, val error: String, val copied: String,
    val tabQuran: String, val tabPrayer: String, val locateTitle: String,
    val locateButton: String, val qiblaButton: String, val imsak: String, val subuh: String,
    val terbit: String, val dzuhur: String, val ashar: String,
    val maghrib: String, val isya: String, val locationFound: String,
    val waitingLocation: String
)

val stringsId = UiStrings(
    "Cari Surat...", "Karrom - Al Quran", "Ayat", "Memuat...", "Gagal", "Teks disalin",
    "Al-Quran", "Jadwal Sholat", "Lokasi Anda", "Update Lokasi", "Arah Kiblat",
    "Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya", "Lokasi: ",
    "Menunggu Lokasi..."
)
val stringsEn = UiStrings(
    "Search Surah...", "Karrom - Al Quran", "Verse", "Loading...", "Error", "Text copied",
    "Quran", "Prayer Times", "Your Location", "Update Location", "Qibla",
    "Imsak", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha", "Location: ",
    "Waiting for Location..."
)

class MainViewModel : ViewModel() {
    private val _language = MutableStateFlow(AppLanguage.INDONESIA)
    val language = _language.asStateFlow()
    val uiStrings = _language.map { if (it == AppLanguage.INDONESIA) stringsId else stringsEn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, stringsId)

    fun toggleLanguage() { _language.value = if (_language.value == AppLanguage.INDONESIA) AppLanguage.ENGLISH else AppLanguage.INDONESIA }

    // Quran State
    private val _surahList = MutableStateFlow<List<SurahSummary>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _detailSurah = MutableStateFlow<SurahDetailData?>(null)
    val detailSurah = _detailSurah.asStateFlow()
    private val _isLoadingQuran = MutableStateFlow(false)
    val isLoadingQuran = _isLoadingQuran.asStateFlow()

    // Prayer & Location State
    private val _prayerTimes = MutableStateFlow<Timings?>(null)
    val prayerTimes = _prayerTimes.asStateFlow()
    private val _locationName = MutableStateFlow("")
    val locationName = _locationName.asStateFlow()
    private val _userCoordinates = MutableStateFlow<Pair<Double, Double>?>(null) // Simpan Lat/Long
    val userCoordinates = _userCoordinates.asStateFlow()
    private val _isLoadingPrayer = MutableStateFlow(false)
    val isLoadingPrayer = _isLoadingPrayer.asStateFlow()

    val filteredSurahList = combine(_surahList, _searchQuery) { list, query ->
        if (query.isBlank()) list else list.filter {
            it.name.transliteration.id.contains(query, true) || it.name.translation.id.contains(query, true) || it.number.toString() == query
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { fetchSurahList() }

    fun onSearch(query: String) { _searchQuery.value = query }

    private fun fetchSurahList() {
        viewModelScope.launch {
            _isLoadingQuran.value = true
            try { _surahList.value = ApiClient.quranApi.getSurahList().data } catch (e: Exception) { e.printStackTrace() } finally { _isLoadingQuran.value = false }
        }
    }

    fun fetchSurahDetail(nomor: Int) {
        viewModelScope.launch {
            _isLoadingQuran.value = true
            _detailSurah.value = null
            try { _detailSurah.value = ApiClient.quranApi.getSurahDetail(nomor).data } catch (e: Exception) { e.printStackTrace() } finally { _isLoadingQuran.value = false }
        }
    }

    fun fetchPrayerAndCity(context: Context, lat: Double, long: Double) {
        viewModelScope.launch {
            _isLoadingPrayer.value = true
            _userCoordinates.value = Pair(lat, long) // Simpan koordinat
            try {
                // Get City Name
                val cityName = withContext(Dispatchers.IO) {
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(lat, long, 1)
                        if (!addresses.isNullOrEmpty()) {
                            addresses[0].subAdminArea ?: addresses[0].locality ?: addresses[0].adminArea
                        } else "Lat: ${String.format("%.2f", lat)}, Long: ${String.format("%.2f", long)}"
                    } catch (e: Exception) { "Lat: ${String.format("%.2f", lat)}, Long: ${String.format("%.2f", long)}" }
                }
                _locationName.value = cityName ?: "Lokasi Tidak Dikenal"

                // Get Prayer Times
                val date = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
                val response = ApiClient.prayerApi.getTimings(date, lat, long)
                _prayerTimes.value = response.data.timings

            } catch (e: Exception) { e.printStackTrace() } finally { _isLoadingPrayer.value = false }
        }
    }
}

// ==============================
// 3. UI LAYER (COMPOSE)
// ==============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Karrom2Theme { MainApp() } }
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

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Quran : Screen("quran", "Al-Quran", Icons.Default.MenuBook)
    object Prayer : Screen("prayer", "Sholat", Icons.Default.AccessTime)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Quran.route

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Karrom2 Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                NavigationDrawerItem(
                    label = { Text(uiStrings.tabQuran) }, selected = currentRoute == Screen.Quran.route,
                    icon = { Icon(Screen.Quran.icon, contentDescription = null) },
                    onClick = {
                        navController.navigate(Screen.Quran.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                        scope.launch { drawerState.close() }
                    }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(uiStrings.tabPrayer) }, selected = currentRoute == Screen.Prayer.route,
                    icon = { Icon(Screen.Prayer.icon, contentDescription = null) },
                    onClick = {
                        navController.navigate(Screen.Prayer.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                        scope.launch { drawerState.close() }
                    }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = if (currentRoute == Screen.Prayer.route) uiStrings.tabPrayer else uiStrings.appTitle, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu") } },
                    actions = { IconButton(onClick = { viewModel.toggleLanguage() }) { Icon(Icons.Default.Language, contentDescription = "Lang") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary)
                )
            }
        ) { innerPadding ->
            NavHost(navController = navController, startDestination = Screen.Quran.route, modifier = Modifier.padding(innerPadding)) {
                composable(Screen.Quran.route) { QuranScreen(navController, viewModel) }
                composable(Screen.Prayer.route) { PrayerScreen(viewModel) }
                composable(route = "detail/{nomor}", arguments = listOf(navArgument("nomor") { type = NavType.IntType })) { backStackEntry ->
                    val nomor = backStackEntry.arguments?.getInt("nomor") ?: 1
                    DetailScreen(navController, viewModel, nomor)
                }
            }
        }
    }
}

// --- SCREEN 1: QURAN LIST ---
@Composable
fun QuranScreen(navController: androidx.navigation.NavController, viewModel: MainViewModel) {
    val surahs by viewModel.filteredSurahList.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoadingQuran.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()
    val currentLang by viewModel.language.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            OutlinedTextField(
                value = query, onValueChange = { viewModel.onSearch(it) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(uiStrings.searchHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp), singleLine = true
            )
        }
        if (isLoading && surahs.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(surahs) { surah -> SurahItem(surah, currentLang) { navController.navigate("detail/${surah.number}") } }
        }
    }
}

@Composable
fun SurahItem(surah: SurahSummary, lang: AppLanguage, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onClick() }, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text(text = surah.number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1.0f)) {
                Text(text = surah.name.transliteration.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = if(lang == AppLanguage.INDONESIA) surah.name.translation.id else surah.name.translation.en, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text(text = surah.name.short, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- SCREEN 2: PRAYER TIMES & COMPASS ---
// --- SCREEN 2: PRAYER TIMES & COMPASS (RESPONSIVE LANDSCAPE) ---
@Composable
fun PrayerScreen(viewModel: MainViewModel) {
    val prayerTimes by viewModel.prayerTimes.collectAsState()
    val locationName by viewModel.locationName.collectAsState()
    val userCoords by viewModel.userCoordinates.collectAsState()
    val isLoading by viewModel.isLoadingPrayer.collectAsState()
    val uiStrings by viewModel.uiStrings.collectAsState()

    val context = LocalContext.current
    var showCompassDialog by remember { mutableStateOf(false) }

    // Konfigurasi Layar (Portrait/Landscape)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            getCurrentLocation(context, fusedLocationClient, viewModel)
        } else { Toast.makeText(context, "Izin lokasi diperlukan", Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (prayerTimes == null) getCurrentLocation(context, fusedLocationClient, viewModel)
        }
    }

    if (showCompassDialog && userCoords != null) {
        QiblaCompassDialog(onDismiss = { showCompassDialog = false }, userLat = userCoords!!.first, userLong = userCoords!!.second)
    }

    // --- LOGIC UI (Dipisah agar rapi) ---
    // 1. Komponen Kartu Lokasi & Tombol
    val locationCardContent = @Composable {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = if(locationName.isEmpty()) uiStrings.waitingLocation else locationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(12.dp))

                // Tombol
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else { getCurrentLocation(context, fusedLocationClient, viewModel) }
                    }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(uiStrings.locateButton, fontSize = 12.sp, textAlign = TextAlign.Center) }

                    OutlinedButton(
                        onClick = {
                            if(userCoords != null) showCompassDialog = true
                            else Toast.makeText(context, "Update lokasi dulu!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f), enabled = userCoords != null,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(uiStrings.qiblaButton, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    // 2. Data Jadwal Sholat (List)
    val prayerList = listOfNotNull(
        prayerTimes?.let { "Imsak" to it.Imsak },
        prayerTimes?.let { "Subuh" to it.Fajr },
        prayerTimes?.let { "Terbit" to it.Sunrise },
        prayerTimes?.let { "Dzuhur" to it.Dhuhr },
        prayerTimes?.let { "Ashar" to it.Asr },
        prayerTimes?.let { "Maghrib" to it.Maghrib },
        prayerTimes?.let { "Isya" to it.Isha }
    )

    // --- TAMPILAN UTAMA ---
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            if (isLandscape) {
                // === LAYOUT LANDSCAPE (Kiri: Lokasi, Kanan: Jadwal 2 Kolom) ===
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Bagian Kiri (Lebar 40%)
                    Column(modifier = Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        locationCardContent()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sumber: Kemenag RI", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }

                    // Bagian Kanan (Lebar 60%) - Menggunakan GRID 2 Kolom agar muat tanpa scroll
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2), // 2 Kolom
                        modifier = Modifier.weight(0.6f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(prayerList) { (name, time) ->
                            val uiName = when(name) {
                                "Imsak" -> uiStrings.imsak; "Subuh" -> uiStrings.subuh; "Terbit" -> uiStrings.terbit
                                "Dzuhur" -> uiStrings.dzuhur; "Ashar" -> uiStrings.ashar; "Maghrib" -> uiStrings.maghrib; else -> uiStrings.isya
                            }
                            PrayerItem(uiName, time, isHighlight = name == "Maghrib")
                        }
                    }
                }
            } else {
                // === LAYOUT PORTRAIT (Atas: Lokasi, Bawah: List Panjang) ===
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    locationCardContent()
                    Spacer(modifier = Modifier.height(16.dp))

                    if (prayerTimes != null) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(prayerList) { (name, time) ->
                                val uiName = when(name) {
                                    "Imsak" -> uiStrings.imsak; "Subuh" -> uiStrings.subuh; "Terbit" -> uiStrings.terbit
                                    "Dzuhur" -> uiStrings.dzuhur; "Ashar" -> uiStrings.ashar; "Maghrib" -> uiStrings.maghrib; else -> uiStrings.isya
                                }
                                PrayerItem(uiName, time, isHighlight = name == "Maghrib")
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Sumber: Kemenag RI (Aladhan API)", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("Silakan update lokasi", color = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- NEW COMPONENT: QIBLA COMPASS DIALOG ---
@Composable
fun QiblaCompassDialog(onDismiss: () -> Unit, userLat: Double, userLong: Double) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val qiblaBearing = remember(userLat, userLong) {
        val kaaba = Location("Kaaba").apply { latitude = 21.422487; longitude = 39.826206 }
        val userLoc = Location("User").apply { latitude = userLat; longitude = userLong }
        userLoc.bearingTo(kaaba)
    }

    var currentAzimuth by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        val accelerometerReading = FloatArray(3)
        val magnetometerReading = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)

                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuthInRadians = orientationAngles[0]
                    var azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
                    if (azimuthInDegrees < 0) azimuthInDegrees += 360f
                    currentAzimuth = azimuthInDegrees
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(sensorListener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(sensorListener, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_UI)

        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    val animatedAzimuth by animateFloatAsState(targetValue = -currentAzimuth, animationSpec = tween(durationMillis = 200), label = "rotation")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Arah Kiblat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Putar HP Anda mencari arah panah Hijau", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))

                Box(contentAlignment = Alignment.Center) {
                    // Piringan Kompas
                    Canvas(modifier = Modifier.size(250.dp).rotate(animatedAzimuth)) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension / 2

                        drawCircle(color = Color.Gray, radius = radius, style = Stroke(width = 4.dp.toPx()))

                        drawContext.canvas.nativeCanvas.apply {
                            drawText("N", center.x, center.y - radius + 40.dp.toPx(), android.graphics.Paint().apply {
                                color = android.graphics.Color.RED; textSize = 40.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true
                            })
                        }

                        drawLine(Color.Gray, start = Offset(center.x, center.y - radius), end = Offset(center.x, center.y - radius + 20), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x, center.y + radius), end = Offset(center.x, center.y + radius - 20), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x - radius, center.y), end = Offset(center.x - radius + 20, center.y), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x + radius, center.y), end = Offset(center.x + radius - 20, center.y), strokeWidth = 5f)
                    }

                    // Jarum Kiblat
                    Canvas(modifier = Modifier.size(200.dp).rotate(animatedAzimuth + qiblaBearing)) {
                        val center = Offset(size.width / 2, size.height / 2)

                        // --- PERBAIKAN DI SINI (Gunakan androidx.compose.ui.graphics.Path) ---
                        val arrowPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(center.x, center.y - size.minDimension / 2 + 20)
                            lineTo(center.x - 20, center.y)
                            lineTo(center.x + 20, center.y)
                            close()
                        }
                        // ---------------------------------------------------------------------

                        drawPath(arrowPath, color = Color(0xFF2E7D32))
                        drawCircle(Color(0xFF2E7D32), radius = 10f, center = center)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onDismiss) { Text("Tutup") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(context: Context, fusedLocationClient: FusedLocationProviderClient, viewModel: MainViewModel) {
    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
        if (location != null) viewModel.fetchPrayerAndCity(context, location.latitude, location.longitude)
        else {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { viewModel.fetchPrayerAndCity(context, it.latitude, it.longitude); fusedLocationClient.removeLocationUpdates(this) }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }.addOnFailureListener { Toast.makeText(context, "Gagal mendapatkan GPS", Toast.LENGTH_SHORT).show() }
}

@Composable
fun PrayerItem(name: String, time: String, isHighlight: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if(isHighlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = if(isHighlight) FontWeight.Bold else FontWeight.Normal)
            Text(text = time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// --- SCREEN 3: DETAIL QURAN (Sama) ---
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
            else { surahDetail?.let { detail -> LazyColumn(contentPadding = PaddingValues(16.dp)) { items(detail.verses) { ayat -> AyatItem(ayat, uiStrings, currentLang, context) } } } }
        }
    }
}

@Composable
fun AyatItem(ayat: Verse, uiStrings: UiStrings, lang: AppLanguage, context: Context) {
    val translationText = if (lang == AppLanguage.INDONESIA) ayat.translation.id else ayat.translation.en
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(text = "${uiStrings.verse} ${ayat.number.inSurah}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; val clip = ClipData.newPlainText("Quran Verse", "QS ${ayat.number.inSurah}\n${ayat.text.arab}\n\n$translationText"); clipboard.setPrimaryClip(clip); Toast.makeText(context, uiStrings.copied, Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray, modifier = Modifier.size(20.dp)) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = ayat.text.arab, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End, lineHeight = 55.sp, fontFamily = FontFamily.Serif, fontSize = 32.sp, fontWeight = FontWeight.Normal))
            Spacer(modifier = Modifier.height(24.dp)); Text(text = ayat.text.transliteration.en, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp)); Text(text = translationText, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
        }
    }
}