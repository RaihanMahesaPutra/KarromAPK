package com.example.karrom2

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.view.Surface // <-- PENTING UNTUK DETEKSI ROTASI
import android.view.WindowManager // <-- PENTING
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI

// ==============================
// 1. DATA LAYER (MODELS)
// ==============================

const val BASE_URL_QURAN = "https://api.quran.gading.dev/"
const val BASE_URL_PRAYER = "https://api.aladhan.com/v1/"

// --- QURAN (FIXED META JUZ) ---
data class SurahResponse(val code: Int, val data: List<SurahSummary>)
data class SurahDetailResponse(val code: Int, val data: SurahDetailData)
data class SurahSummary(val number: Int, val name: SurahName, val revelation: Revelation)
data class SurahDetailData(val number: Int, val name: SurahName, val revelation: Revelation, val verses: List<Verse>)
data class SurahName(val short: String, val transliteration: TranslationBlock, val translation: TranslationBlock)
data class Revelation(val id: String)

// Update Verse: Menambahkan Meta (Juz)
data class Verse(
    val number: VerseNumber,
    val text: VerseText,
    val translation: TranslationBlock,
    val meta: VerseMeta?
)
data class VerseMeta(val juz: Int) // Model untuk Juz

data class VerseNumber(val inSurah: Int)
data class VerseText(val arab: String, val transliteration: TransliterationDetail)
data class TransliterationDetail(val en: String)
data class TranslationBlock(val en: String, val id: String)

// --- JADWAL SHOLAT ---
data class PrayerResponse(val code: Int, val data: PrayerData)
data class PrayerData(val timings: Timings)
data class Timings(val Fajr: String, val Sunrise: String, val Dhuhr: String, val Asr: String, val Maghrib: String, val Isha: String, val Imsak: String)

// --- DATA LOKAL MODELS ---
data class DoaItem(val id: Int, val judul: String, val judulEn: String, val arab: String, val latin: String, val terjemahan: String, val terjemahanEn: String)
data class AsmaulHusnaItem(val id: Int, val arab: String, val latin: String, val indo: String, val en: String)

// ==============================
// 2. API CLIENTS
// ==============================

interface QuranApi {
    @GET("surah") suspend fun getSurahList(): SurahResponse
    @GET("surah/{number}") suspend fun getSurahDetail(@Path("number") number: Int): SurahDetailResponse
}
interface PrayerApi {
    @GET("timings/{date}") suspend fun getTimings(@Path("date") date: String, @Query("latitude") lat: Double, @Query("longitude") long: Double, @Query("method") method: Int = 20): PrayerResponse
}

object ApiClient {
    val quranApi: QuranApi by lazy { Retrofit.Builder().baseUrl(BASE_URL_QURAN).addConverterFactory(GsonConverterFactory.create()).build().create(QuranApi::class.java) }
    val prayerApi: PrayerApi by lazy { Retrofit.Builder().baseUrl(BASE_URL_PRAYER).addConverterFactory(GsonConverterFactory.create()).build().create(PrayerApi::class.java) }
}

// ==============================
// 3. STATIC DATA (UPDATED: 20 DOA)
// ==============================
object LocalData {
    val listDoa = listOf(
        DoaItem(1, "Doa Sebelum Makan", "Prayer Before Eating", "اَللّٰهُمَّ بَارِكْ لَنَا فِيمَا رَزَقْتَنَا وَقِنَا عَذَابَ النَّارِ", "Allahumma baarik lanaa fiimaa rozaqtanaa wa qinaa 'adzaaban naar", "Ya Allah, berkahilah kami dalam rezeki yang telah Engkau berikan kepada kami dan peliharalah kami dari siksa api neraka", "O Allah, bless us in what You have provided for us and save us from the punishment of the Fire"),
        DoaItem(2, "Doa Sesudah Makan", "Prayer After Eating", "اَلْحَمْدُ ِللهِ الَّذِىْ اَطْعَمَنَا وَسَقَانَا وَجَعَلَنَا مُسْلِمِيْنَ", "Alhamdulillaahil ladzi ath'amanaa wa saqoonaa wa ja'alanaa muslimiin", "Segala puji bagi Allah yang telah memberi makan kami dan minuman kami, serta menjadikan kami sebagai orang-orang islam", "Praise be to Allah Who has fed us and given us drink, and made us Muslims"),
        DoaItem(3, "Doa Sebelum Tidur", "Prayer Before Sleeping", "بِسْمِكَ اللّهُمَّ اَحْيَا وَ بِسْمِكَ اَمُوْتُ", "Bismikalloohumma ahyaa wa bismika amuut", "Dengan nama-Mu ya Allah aku hidup, dan dengan nama-Mu aku mati", "In Your Name, O Allah, I live and I die"),
        DoaItem(4, "Doa Bangun Tidur", "Prayer After Waking Up", "اَلْحَمْدُ ِللهِ الَّذِىْ اَحْيَانَا بَعْدَمَا اَمَاتَنَا وَاِلَيْهِ النُّشُوْرُ", "Alhamdulillaahil ladzi ahyaanaa ba'da maa amaatanaa wa ilaihin nusyuur", "Segala puji bagi Allah yang telah menghidupkan kami sesudah kami mati (membangunkan dari tidur) dan hanya kepada-Nya kami dikembalikan", "Praise is to Allah Who gives us life after He has caused us to die and to Him is the return"),
        DoaItem(5, "Doa Masuk Masjid", "Prayer Entering Mosque", "اَللّٰهُمَّ افْتَحْ لِيْ اَبْوَابَ رَحْمَتِكَ", "Allahummaf tahlii abwaaba rohmatik", "Ya Allah, bukalah untukku pintu-pintu rahmat-Mu", "O Allah, open for me the doors of Your mercy"),
        DoaItem(6, "Doa Keluar Masjid", "Prayer Exiting Mosque", "اَللّٰهُمَّ اِنِّى اَسْأَلُكَ مِنْ فَضْلِكَ", "Allahumma innii as-aluka min fadhlik", "Ya Allah, sesungguhnya aku memohon keutamaan dari-Mu", "O Allah, I ask You from Your bounty"),
        DoaItem(7, "Doa Masuk Kamar Mandi", "Prayer Entering Toilet", "اَللّٰهُمَّ اِنِّيْ اَعُوْذُ بِكَ مِنَ الْخُبُثِ وَالْخَبَآئِثِ", "Allahumma innii a'uudzu bika minal khubutsi wal khobaaits", "Ya Allah, sesungguhnya aku berlindung kepada-Mu dari godaan syetan laki-laki dan perempuan", "O Allah, I seek refuge with You from all evil and evil-doers"),
        DoaItem(8, "Doa Keluar Kamar Mandi", "Prayer Exiting Toilet", "غُفْرَانَكَ الْحَمْدُ ِللهِ الَّذِىْ اَذْهَبَ عَنِّى الْاَذَى وَعَافَانِى", "Ghufroonakal hamdu lillaahil ladzii adzhaba 'annil adzaa wa 'aafaanii", "Dengan mengharap ampunan-Mu, segala puji milik Allah yang telah menghilangkan kotoran dari badanku dan yang telah menyejahterakan", "I ask Your forgiveness. Praise be to Allah who removed the harm from me and gave me health"),
        DoaItem(9, "Doa Memakai Pakaian", "Prayer Wearing Clothes", "بِسْمِ اللهِ اَللّٰهُمَّ اِنِّى اَسْأَلُكَ مِنْ خَيْرِهِ وَخَيْرِ مَاهُوَ لَهُ وَاَعُوْذُ بِكَ مِنْ شَرِّهِ وَشَرِّ مَاهُوَ لَهُ", "Bismillaahi, Alloohumma innii as-aluka min khoirihi wa khoiri maa huwa lahu, wa a'uudzu bika min syarrihi wa syarri maa huwa lahu", "Dengan nama-Mu ya Allah aku minta kepada Engkau kebaikan pakaian ini dan kebaikan apa yang ada padanya, dan aku berlindung kepada Engkau dari kejahatan pakaian ini dan kejahatan yang ada padanya", "In the name of Allah. O Allah, I ask You for the good of it and the good of what it is for, and I seek refuge in You from the evil of it and the evil of what it is for"),
        DoaItem(10, "Doa Bercermin", "Prayer Looking in Mirror", "اَللّٰهُمَّ كَمَا حَسَّنْتَ خَلْقِيْ فَحَسِّنْ خُلُقِيْ", "Allahumma kamaa hassanta kholqii fahassin khuluqii", "Ya Allah, sebagaimana Engkau telah membaguskan penciptaanku, maka baguskanlah pula akhlakku", "O Allah, just as You have made my creation good, make my character good"),
        DoaItem(11, "Doa Keluar Rumah", "Prayer Leaving House", "بِسْمِ اللهِ تَوَكَّلْتُ عَلَى اللهِ، لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللهِ", "Bismillaahi tawakkaltu 'alallooh, laa hawla wa laa quwwata illaa billaah", "Dengan nama Allah, aku bertawakkal kepada Allah. Tiada daya dan kekuatan kecuali dengan Allah", "In the name of Allah, I place my trust in Allah. There is no might nor power except with Allah"),
        DoaItem(12, "Doa Masuk Rumah", "Prayer Entering House", "بِسْمِ اللهِ وَلَجْنَا، وَبِسْمِ اللهِ خَرَجْنَا، وَعَلَى رَبِّنَا تَوَكَّلْنَا", "Bismillaahi walajnaa wa bismillaahi khorojnaa wa 'alaa robbinaa tawakkalnaa", "Dengan nama Allah kami masuk rumah, dengan nama Allah kami keluar rumah, dan kepada Tuhan kami, kami bertawakkal", "In the name of Allah we enter, and in the name of Allah we leave, and upon our Lord we rely"),
        DoaItem(13, "Doa Naik Kendaraan", "Prayer Riding Vehicle", "سُبْحَانَ الَّذِيْ سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِيْنَ. وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُوْنَ", "Subhaanal ladzii sakh-khoro lanaa haadzaa wa maa kunnaa lahu muqriniin. Wa innaa ilaa robbinaa lamunqolibuun", "Maha Suci Tuhan yang telah menundukkan semua ini bagi kami padahal kami sebelumnya tidak mampu menguasainya, dan sesungguhnya kami akan kembali kepada Tuhan kami", "Glory to Him who has subjected this to us, and we could not have otherwise subdued it. And indeed we, to our Lord, will return"),
        DoaItem(14, "Doa Belajar", "Prayer Before Studying", "رَبِّ زِدْنِي عِلْمًا وَارْزُقْنِيْ فَهْمًا وَاجْعَلْنِيْ مِنَ الصَّالِحِيْنَ", "Robbi zidnii 'ilman warzuqnii fahman waj'alnii minash shoolihiin", "Ya Allah, tambahkanlah aku ilmu dan berikanlah aku rizqi akan kepahaman, dan jadikanlah aku termasuk golongan orang-orang yang sholeh", "My Lord, increase me in knowledge and grant me understanding and include me among the righteous"),
        DoaItem(15, "Doa Setelah Belajar", "Prayer After Studying", "اَللّٰهُمَّ اِنِّى اِسْتَوْدِعُكَ مَا عَلَّمْتَنِيْهِ فَارْدُدْهُ اِلَىَّ عِنْدَ حَاجَتِى وَلاَ تَنْسَنِيْهِ يَا رَبَّ الْعَالَمِيْنَ", "Allaahumma innii istaudi'uka maa 'allamtaniihi fardud-hu ilayya 'inda haajatii wa laa tansaniihi yaa robbal 'alamiin", "Ya Allah, sesungguhnya aku menitipkan kepada-Mu apa yang telah Engkau ajarkan kepadaku, maka kembalikanlah ia kepadaku ketika aku membutuhkannya. Dan janganlah Engkau lupakan aku daripadanya, ya Tuhan semesta alam", "O Allah, I entrust You with what You have taught me, so return it to me when I need it and do not make me forget it, O Lord of the worlds"),
        DoaItem(16, "Doa Menjenguk Orang Sakit", "Prayer Visiting Sick", "اللَّهُمَّ رَبَّ النَّاسِ أَذْهِبِ الْبَأْسَ اشْفِ أَنْتَ الشَّافِي لَا شَافِيَ إلَّا أَنْتَ شِفَاءً لَا يُغَادِرُ سَقْمًا", "Allahumma rabban naas adzhibil ba’sa isyfi antash syaafi laa syaafiya illaa anta syifaa’an laa yughaadiru saqman", "Ya Allah, Tuhan manusia, hilangkanlah penyakit ini, sembuhkanlah, Engkaulah Yang Maha Penyembuh, tidak ada kesembuhan kecuali kesembuhan dari-Mu, kesembuhan yang tidak meninggalkan penyakit", "O Allah, Lord of mankind, remove the severity and cure. You are the Healer, there is no cure but Your cure, a cure that leaves no illness"),
        DoaItem(17, "Doa Turun Hujan", "Prayer When Raining", "اللَّهُمَّ صَيِّبًا نَافِعًا", "Allahumma shoyyiban naafi’an", "Ya Allah, turunkanlah pada kami hujan yang bermanfaat", "O Allah, may it be a beneficial rain"),
        DoaItem(18, "Doa Ketika Mendengar Petir", "Prayer Hearing Thunder", "سُبْحَانَ الَّذِي يُسَبِّحُ الرَّعْدُ بِحَمْدِهِ وَالْمَلَائِكَةُ مِنْ خِيفَتِهِ", "Subhaanalladzi yusabbihur ro’du bihamdihi wal malaaikatu min khiifatihi", "Maha Suci Allah yang petir bertasbih dengan memuji-Nya dan para malaikat takut kepada-Nya", "Glory be to Him whom thunder praises with His praise, and the angels from the fear of Him"),
        DoaItem(19, "Doa Kebaikan Dunia Akhirat", "Prayer for Goodness", "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ حَسَنَةً وَقِنَا عَذَابَ النَّارِ", "Rabbanaa aatinaa fid dunyaa hasanah wa fil aakhirati hasanah wa qinaa 'adzaaban naar", "Ya Tuhan kami, berilah kami kebaikan di dunia dan kebaikan di akhirat dan peliharalah kami dari siksa neraka", "Our Lord, give us in this world [that which is] good and in the Hereafter [that which is] good and protect us from the punishment of the Fire"),
        DoaItem(20, "Doa Untuk Kedua Orang Tua", "Prayer for Parents", "رَبِّ اغْفِرْ لِيْ وَلِوَالِدَيَّ وَارْحَمْهُمَا كَمَا رَبَّيَانِيْ صَغِيْرًا", "Robbighfir lii wa li waalidayya warhamhumaa kamaa robbayaanii shoghiiroo", "Ya Tuhanku, ampunilah dosaku dan dosa kedua orang tuaku, dan sayangilah keduanya sebagaimana mereka menyayangi aku di waktu kecil", "My Lord, forgive me and my parents and have mercy upon them as they brought me up [when I was] small")
    )

    val listAsmaulHusna = listOf(
        AsmaulHusnaItem(1, "الرَّحْمَنُ", "Ar Rahman", "Maha Pengasih", "The Beneficent"),
        AsmaulHusnaItem(2, "الرَّحِيمُ", "Ar Rahiim", "Maha Penyayang", "The Merciful"),
        AsmaulHusnaItem(3, "الْمَلِكُ", "Al Malik", "Maha Merajai", "The King"),
        AsmaulHusnaItem(4, "الْقُدُّوسُ", "Al Quddus", "Maha Suci", "The Most Holy"),
        AsmaulHusnaItem(5, "السَّلاَمُ", "As Salaam", "Maha Memberi Kesejahteraan", "The Source of Peace"),
        AsmaulHusnaItem(6, "الْمُؤْمِنُ", "Al Mu`min", "Maha Memberi Keamanan", "The Guardian of Faith"),
        AsmaulHusnaItem(7, "الْمُهَيْمِنُ", "Al Muhaimin", "Maha Pemelihara", "The Protector"),
        AsmaulHusnaItem(8, "الْعَزِيزُ", "Al `Aziiz", "Maha Perkasa", "The Mighty"),
        AsmaulHusnaItem(9, "الْجَبَّارُ", "Al Jabbar", "Maha Memiliki Mutlak Kegagahan", "The Compeller"),
        AsmaulHusnaItem(10, "الْمُتَكَبِّرُ", "Al Mutakabbir", "Maha Megah", "The Majestic"),
        AsmaulHusnaItem(11, "الْخَالِقُ", "Al Khaliq", "Maha Pencipta", "The Creator"),
        AsmaulHusnaItem(12, "الْبَارِئُ", "Al Baari`", "Maha Melepaskan", "The Evolver"),
        AsmaulHusnaItem(13, "الْمُصَوِّرُ", "Al Mushawwir", "Maha Membentuk Rupa", "The Fashioner"),
        AsmaulHusnaItem(14, "الْغَفَّارُ", "Al Ghaffaar", "Maha Pengampun", "The Forgiver"),
        AsmaulHusnaItem(15, "الْقَهَّارُ", "Al Qahhaar", "Maha Memaksa", "The Subduer"),
        AsmaulHusnaItem(16, "الْوَهَّابُ", "Al Wahhaab", "Maha Pemberi Karunia", "The Bestower"),
        AsmaulHusnaItem(17, "الرَّزَّاقُ", "Ar Razzaaq", "Maha Pemberi Rezeki", "The Provider"),
        AsmaulHusnaItem(18, "الْفَتَّاحُ", "Al Fattaah", "Maha Pembuka Rahmat", "The Opener"),
        AsmaulHusnaItem(19, "الْعَلِيمُ", "Al `Aliim", "Maha Mengetahui", "The All-Knowing"),
        AsmaulHusnaItem(20, "الْقَابِضُ", "Al Qaabidh", "Maha Menyempitkan", "The Constrictor"),
        AsmaulHusnaItem(21, "الْبَاسِطُ", "Al Baasith", "Maha Melapangkan", "The Expander"),
        AsmaulHusnaItem(22, "الْخَافِضُ", "Al Khaafidh", "Maha Merendahkan", "The Abaser"),
        AsmaulHusnaItem(23, "الرَّافِعُ", "Ar Raafi`", "Maha Meninggikan", "The Exalter"),
        AsmaulHusnaItem(24, "الْمُعِزُّ", "Al Mu`izz", "Maha Memuliakan", "The Honorer"),
        AsmaulHusnaItem(25, "الْمُذِلُّ", "Al Mudzil", "Maha Menghinakan", "The Dishonorer"),
        AsmaulHusnaItem(26, "السَّمِيعُ", "As Samii`", "Maha Mendengar", "The All-Hearing"),
        AsmaulHusnaItem(27, "الْبَصِيرُ", "Al Bashiir", "Maha Melihat", "The All-Seeing"),
        AsmaulHusnaItem(28, "الْحَكَمُ", "Al Hakam", "Maha Menetapkan", "The Judge"),
        AsmaulHusnaItem(29, "الْعَدْلُ", "Al `Adl", "Maha Adil", "The Just"),
        AsmaulHusnaItem(30, "اللَّطِيفُ", "Al Lathiif", "Maha Lembut", "The Latif"),
        AsmaulHusnaItem(31, "الْخَبِيرُ", "Al Khabiiir", "Maha Mengenal", "The All-Aware"),
        AsmaulHusnaItem(32, "الْحَلِيمُ", "Al Haliim", "Maha Penyantun", "The Forbearing"),
        AsmaulHusnaItem(33, "الْعَظِيمُ", "Al `Azhiim", "Maha Agung", "The Magnificent"),
        AsmaulHusnaItem(34, "الْغَفُورُ", "Al Ghafuur", "Maha Memberi Pengampunan", "The Forgiver"),
        AsmaulHusnaItem(35, "الشَّكُورُ", "As Syakuur", "Maha Pembalas Budi", "The Grateful"),
        AsmaulHusnaItem(36, "الْعَلِيُّ", "Al `Aliy", "Maha Tinggi", "The High"),
        AsmaulHusnaItem(37, "الْكَبِيرُ", "Al Kabiir", "Maha Besar", "The Great"),
        AsmaulHusnaItem(38, "الْحَفِيظُ", "Al Hafizh", "Maha Memelihara", "The Preserver"),
        AsmaulHusnaItem(39, "الْمُقِيتُ", "Al Muqiit", "Maha Pemberi Kecukupan", "The Nourisher"),
        AsmaulHusnaItem(40, "الْحَسِيبُ", "Al Hasiib", "Maha Membuat Perhitungan", "The Reckoner"),
        AsmaulHusnaItem(41, "الْجَلِيلُ", "Al Jaliil", "Maha Luhur", "The Majestic"),
        AsmaulHusnaItem(42, "الْكَرِيمُ", "Al Kariim", "Maha Pemurah", "The Generous"),
        AsmaulHusnaItem(43, "الرَّقِيبُ", "Ar Raqiib", "Maha Mengawasi", "The Watchful"),
        AsmaulHusnaItem(44, "الْمُجِيبُ", "Al Mujiib", "Maha Mengabulkan", "The Responder"),
        AsmaulHusnaItem(45, "الْوَاسِعُ", "Al Waasi`", "Maha Luas", "The All-Encompassing"),
        AsmaulHusnaItem(46, "الْحَكِيمُ", "Al Hakiim", "Maha Bijaksana", "The Wise"),
        AsmaulHusnaItem(47, "الْوَدُودُ", "Al Waduud", "Maha Mengasihi", "The Loving"),
        AsmaulHusnaItem(48, "الْمَجِيدُ", "Al Majiid", "Maha Mulia", "The Glorious"),
        AsmaulHusnaItem(49, "الْبَاعِثُ", "Al Baa`its", "Maha Membangkitkan", "The Resurrector"),
        AsmaulHusnaItem(50, "الشَّهِيدُ", "As Syahiid", "Maha Menyaksikan", "The Witness"),
        AsmaulHusnaItem(51, "الْحَقُّ", "Al Haqq", "Maha Benar", "The Truth"),
        AsmaulHusnaItem(52, "الْوَكِيلُ", "Al Wakiil", "Maha Memelihara", "The Trustee"),
        AsmaulHusnaItem(53, "الْقَوِيُّ", "Al Qawiyyu", "Maha Kuat", "The Strong"),
        AsmaulHusnaItem(54, "الْمَتِينُ", "Al Matiin", "Maha Kokoh", "The Firm"),
        AsmaulHusnaItem(55, "الْوَلِيُّ", "Al Waliyy", "Maha Melindungi", "The Protecting Friend"),
        AsmaulHusnaItem(56, "الْحَمِيدُ", "Al Hamiid", "Maha Terpuji", "The Praiseworthy"),
        AsmaulHusnaItem(57, "الْمُحْصِي", "Al Muhshii", "Maha Mengalkulasi", "The Counter"),
        AsmaulHusnaItem(58, "الْمُبْدِئُ", "Al Mubdi`", "Maha Memulai", "The Originator"),
        AsmaulHusnaItem(59, "الْمُعِيدُ", "Al Mu`iid", "Maha Mengembalikan Kehidupan", "The Restorer"),
        AsmaulHusnaItem(60, "الْمُحْيِي", "Al Muhyii", "Maha Menghidupkan", "The Giver of Life"),
        AsmaulHusnaItem(61, "الْمُمِيتُ", "Al Mumiit", "Maha Mematikan", "The Taker of Life"),
        AsmaulHusnaItem(62, "الْحَيُّ", "Al Hayyu", "Maha Hidup", "The Living"),
        AsmaulHusnaItem(63, "الْقَيُّومُ", "Al Qayyum", "Maha Mandiri", "The Self-Subsisting"),
        AsmaulHusnaItem(64, "الْوَاجِدُ", "Al Waajid", "Maha Penemu", "The Finder"),
        AsmaulHusnaItem(65, "الْمَاجِدُ", "Al Maajid", "Maha Mulia", "The Noble"),
        AsmaulHusnaItem(66, "الْوَاحِدُ", "Al Wahid", "Maha Tunggal", "The One"),
        AsmaulHusnaItem(67, "الْاَحَدُ", "Al Ahad", "Maha Esa", "The Unique"),
        AsmaulHusnaItem(68, "الصَّمَدُ", "As Shamad", "Maha Dibutuhkan", "The Eternal"),
        AsmaulHusnaItem(69, "الْقَادِرُ", "Al Qaadir", "Maha Menentukan", "The Able"),
        AsmaulHusnaItem(70, "الْمُقْتَدِرُ", "Al Muqtadir", "Maha Berkuasa", "The Powerful"),
        AsmaulHusnaItem(71, "الْمُقَدِّمُ", "Al Muqaddim", "Maha Mendahulukan", "The Expediter"),
        AsmaulHusnaItem(72, "الْمُؤَخِّرُ", "Al Mu`akkhir", "Maha Mengakhirkan", "The Delayer"),
        AsmaulHusnaItem(73, "الْاَوَّلُ", "Al Awwal", "Maha Awal", "The First"),
        AsmaulHusnaItem(74, "الْاٰخِرُ", "Al Aakhir", "Maha Akhir", "The Last"),
        AsmaulHusnaItem(75, "الظَّاهِرُ", "Az Zhaahir", "Maha Nyata", "The Manifest"),
        AsmaulHusnaItem(76, "الْبَاطِنُ", "Al Baathin", "Maha Ghaib", "The Hidden"),
        AsmaulHusnaItem(77, "الْوَالِي", "Al Waali", "Maha Memerintah", "The Governor"),
        AsmaulHusnaItem(78, "الْمُتَعَالِي", "Al Muta`aalii", "Maha Tinggi", "The Most Exalted"),
        AsmaulHusnaItem(79, "الْبَرُّ", "Al Barr", "Maha Penderma", "The Source of Goodness"),
        AsmaulHusnaItem(80, "التَّوَّابُ", "At Tawwaab", "Maha Penerima Taubat", "The Acceptor of Repentance"),
        AsmaulHusnaItem(81, "الْمُنْتَقِمُ", "Al Muntaqim", "Maha Pemberi Balasan", "The Avenger"),
        AsmaulHusnaItem(82, "العَفُوُّ", "Al Afuww", "Maha Pemaaf", "The Pardoner"),
        AsmaulHusnaItem(83, "الرَّؤُوفُ", "Ar Ra`uuf", "Maha Pengasuh", "The Compassionate"),
        AsmaulHusnaItem(84, "مَالِكُ الْمُلْكِ", "Malikul Mulk", "Maha Penguasa Kerajaan", "The Eternal Owner of Sovereignty"),
        AsmaulHusnaItem(85, "ذُو الْجَلاَلِ وَالْاِكْرَامِ", "Dzul Jalaali Wal Ikraam", "Maha Pemilik Kebesaran dan Kemuliaan", "The Lord of Majesty and Bounty"),
        AsmaulHusnaItem(86, "الْمُقْسِطُ", "Al Muqsith", "Maha Pemberi Keadilan", "The Equitable"),
        AsmaulHusnaItem(87, "الْجَامِعُ", "Al Jaami`", "Maha Mengumpulkan", "The Gatherer"),
        AsmaulHusnaItem(88, "الْغَنِيُّ", "Al Ghaniyy", "Maha Kaya", "The Self-Subsisting"),
        AsmaulHusnaItem(89, "الْمُغْنِي", "Al Mughni", "Maha Pemberi Kekayaan", "The Enricher"),
        AsmaulHusnaItem(90, "الْمَانِعُ", "Al Maani", "Maha Mencegah", "The Preventer"),
        AsmaulHusnaItem(91, "الضَّارُّ", "Ad Dhaar", "Maha Penimpa Kemudharatan", "The Distresser"),
        AsmaulHusnaItem(92, "النَّافِعُ", "An Naafi`", "Maha Memberi Manfaat", "The Propitious"),
        AsmaulHusnaItem(93, "النُّورُ", "An Nuur", "Maha Bercahaya", "The Light"),
        AsmaulHusnaItem(94, "الْهَادِي", "Al Haadi", "Maha Pemberi Petunjuk", "The Guide"),
        AsmaulHusnaItem(95, "الْبَدِيعُ", "Al Badii`", "Maha Pencipta", "The Incomparable"),
        AsmaulHusnaItem(96, "الْبَاقِي", "Al Baaqii", "Maha Kekal", "The Everlasting"),
        AsmaulHusnaItem(97, "الْوَارِثُ", "Al Waarits", "Maha Pewaris", "The Supreme Inheritor"),
        AsmaulHusnaItem(98, "الرَّشِيدُ", "Ar Rasyiid", "Maha Pandai", "The Guide to the Right Path"),
        AsmaulHusnaItem(99, "الصَّبُورُ", "As Shabuur", "Maha Sabar", "The Patient")
    )
}

// ==============================
// 4. VIEW MODEL
// ==============================

enum class AppLanguage { INDONESIA, ENGLISH }

data class UiStrings(
    val searchHint: String, val searchAsmaul: String, val searchDoa: String,
    val appTitle: String, val verse: String,
    val loading: String, val error: String, val copied: String,
    val tabQuran: String, val tabPrayer: String, val tabAsmaul: String, val tabDoa: String,
    val locateTitle: String, val locateButton: String, val qiblaButton: String,
    val imsak: String, val subuh: String, val terbit: String, val dzuhur: String, val ashar: String,
    val maghrib: String, val isya: String, val waitingLocation: String
)

val stringsId = UiStrings(
    "Cari Surat...", "Cari Nama Allah...", "Cari Doa...",
    "Al-Quran", "Ayat", "Memuat...", "Gagal", "Teks disalin",
    "Al-Quran", "Jadwal Sholat", "Asmaul Husna", "Doa Harian",
    "Lokasi Anda", "Update Lokasi", "Arah Kiblat",
    "Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya", "Menunggu Lokasi..."
)
val stringsEn = UiStrings(
    "Search Surah...", "Search Name of Allah...", "Search Prayer...",
    "Quran", "Verse", "Loading...", "Error", "Text copied",
    "Quran", "Prayer Times", "Asmaul Husna", "Daily Prayers",
    "Your Location", "Update Location", "Qibla Compass",
    "Imsak", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha", "Waiting for Location..."
)

class MainViewModel : ViewModel() {
    private val _language = MutableStateFlow(AppLanguage.INDONESIA)
    val language = _language.asStateFlow()
    val uiStrings = _language.map { if (it == AppLanguage.INDONESIA) stringsId else stringsEn }.stateIn(viewModelScope, SharingStarted.Eagerly, stringsId)

    fun toggleLanguage() { _language.value = if (_language.value == AppLanguage.INDONESIA) AppLanguage.ENGLISH else AppLanguage.INDONESIA }

    // Search
    private val _quranQuery = MutableStateFlow("")
    val quranQuery = _quranQuery.asStateFlow()
    private val _asmaulQuery = MutableStateFlow("")
    val asmaulQuery = _asmaulQuery.asStateFlow()
    private val _doaQuery = MutableStateFlow("")
    val doaQuery = _doaQuery.asStateFlow()

    // Data
    private val _surahList = MutableStateFlow<List<SurahSummary>>(emptyList())
    private val _loadedSurahs = MutableStateFlow<List<SurahDetailData>>(emptyList())
    val loadedSurahs = _loadedSurahs.asStateFlow()

    private val _prayerTimes = MutableStateFlow<Timings?>(null)
    val prayerTimes = _prayerTimes.asStateFlow()
    private val _locationName = MutableStateFlow("")
    val locationName = _locationName.asStateFlow()
    private val _userCoordinates = MutableStateFlow<Pair<Double, Double>?>(null)
    val userCoordinates = _userCoordinates.asStateFlow()

    // Local Data States
    private val _asmaulHusnaList = MutableStateFlow<List<AsmaulHusnaItem>>(emptyList())
    private val _doaList = MutableStateFlow<List<DoaItem>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Filters
    val filteredSurahList = combine(_surahList, _quranQuery) { list, query ->
        if (query.isBlank()) list else list.filter { it.name.transliteration.id.contains(query, true) || it.name.translation.id.contains(query, true) || it.number.toString() == query }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAsmaulHusna = combine(_asmaulHusnaList, _asmaulQuery) { list, query ->
        if (query.isBlank()) list else list.filter { it.latin.contains(query, true) || it.indo.contains(query, true) || it.en.contains(query, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredDoaList = combine(_doaList, _doaQuery) { list, query ->
        if (query.isBlank()) list else list.filter { it.judul.contains(query, true) || it.judulEn.contains(query, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { fetchSurahList(); fetchAsmaulHusna(); fetchDoaList() }

    fun onSearchQuran(q: String) { _quranQuery.value = q }
    fun onSearchAsmaul(q: String) { _asmaulQuery.value = q }
    fun onSearchDoa(q: String) { _doaQuery.value = q }

    private fun fetchSurahList() { viewModelScope.launch { _isLoading.value = true; try { _surahList.value = ApiClient.quranApi.getSurahList().data } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false } } }
    private fun fetchAsmaulHusna() { _asmaulHusnaList.value = LocalData.listAsmaulHusna }
    private fun fetchDoaList() { _doaList.value = LocalData.listDoa }

    // FIX BUG NAVIGATION: Hanya load jika list kosong ATAU surat yang diminta beda
    fun openSurah(number: Int) {
        val current = _loadedSurahs.value.firstOrNull()
        if (_loadedSurahs.value.isEmpty() || current?.number != number) {
            _loadedSurahs.value = emptyList()
            fetchAndAppendSurah(number)
        }
    }

    fun loadNextSurah() { val last = _loadedSurahs.value.lastOrNull(); if (last != null && last.number < 114 && !_isLoading.value) fetchAndAppendSurah(last.number + 1) }
    private fun fetchAndAppendSurah(n: Int) { viewModelScope.launch { _isLoading.value = true; try { val new = ApiClient.quranApi.getSurahDetail(n).data; _loadedSurahs.value += new } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false } } }
    fun fetchPrayerAndCity(ctx: Context, lat: Double, long: Double) { viewModelScope.launch { _isLoading.value = true; _userCoordinates.value = Pair(lat, long); try { val city = withContext(Dispatchers.IO) { try { val g = Geocoder(ctx, Locale.getDefault()); val a = g.getFromLocation(lat, long, 1); if (!a.isNullOrEmpty()) a[0].subAdminArea ?: a[0].locality else "Lat: ${String.format("%.2f", lat)}" } catch (e: Exception) { "Lat: ${String.format("%.2f", lat)}" } }; _locationName.value = city ?: "Lokasi Tidak Dikenal"; val d = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date()); _prayerTimes.value = ApiClient.prayerApi.getTimings(d, lat, long).data.timings } catch (e: Exception) { e.printStackTrace() } finally { _isLoading.value = false } } }
}

// ==============================
// 5. UI LAYER (COMPOSE)
// ==============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Karrom2Theme { MainApp() } }
    }
}

@Composable
fun Karrom2Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF0F3935), secondary = Color(0xFFFDE68A), onSecondary = Color(0xFF451B00), background = Color(0xFF111827), surface = Color(0xFF1F2937), onSurface = Color(0xFFF9FAFB), primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1), secondaryContainer = Color(0xFF4B3C2A), onSecondaryContainer = Color(0xFFFDE68A))
    else lightColorScheme(primary = Color(0xFF0F766E), onPrimary = Color.White, secondary = Color(0xFFD97706), onSecondary = Color.White, background = Color(0xFFF0FDFA), surface = Color.White, onSurface = Color(0xFF111827), primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF0F514D), secondaryContainer = Color(0xFFFEF3C7), onSecondaryContainer = Color(0xFF78350F))
    val view = androidx.compose.ui.platform.LocalView.current; if (!view.isInEditMode) SideEffect { val w = (view.context as android.app.Activity).window; w.statusBarColor = android.graphics.Color.BLACK; androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = false }; MaterialTheme(colorScheme = colors, content = content)
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Quran : Screen("quran", "Al-Quran", Icons.Default.MenuBook)
    object Prayer : Screen("prayer", "Sholat", Icons.Default.AccessTime)
    object Asmaul : Screen("asmaul", "Asmaul Husna", Icons.Default.Star)
    object Doa : Screen("doa", "Doa Harian", Icons.Default.VolunteerActivism)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController(); val vm: MainViewModel = viewModel(); val uiStrings by vm.uiStrings.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState(); val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Quran.route

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
            Spacer(modifier = Modifier.height(24.dp)); Text("Karrom Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            listOf(Triple(Screen.Quran, uiStrings.tabQuran, Screen.Quran.icon), Triple(Screen.Prayer, uiStrings.tabPrayer, Screen.Prayer.icon), Triple(Screen.Asmaul, uiStrings.tabAsmaul, Screen.Asmaul.icon), Triple(Screen.Doa, uiStrings.tabDoa, Screen.Doa.icon)).forEach { (s, l, i) ->
                NavigationDrawerItem(label = { Text(l, fontWeight = FontWeight.SemiBold) }, selected = currentRoute == s.route, icon = { Icon(i, null) }, colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary), onClick = { navController.navigate(s.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }; scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            }
        }
    }) {
        Scaffold(topBar = {
            Column {
                TopAppBar(
                    title = { Text(when (currentRoute) { Screen.Prayer.route -> uiStrings.tabPrayer; Screen.Asmaul.route -> uiStrings.tabAsmaul; Screen.Doa.route -> uiStrings.tabDoa; else -> uiStrings.appTitle }, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton({ scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                    actions = { IconButton({ vm.toggleLanguage() }) { Icon(Icons.Default.Language, "Lang") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                )
            }
        }) { padding ->
            NavHost(navController, startDestination = Screen.Quran.route, modifier = Modifier.padding(padding)) {
                composable(Screen.Quran.route) { QuranScreen(navController, vm) }
                composable(Screen.Prayer.route) { PrayerScreen(vm) }
                composable(Screen.Asmaul.route) { AsmaulHusnaScreen(vm) }
                composable(Screen.Doa.route) { DoaScreen(vm) }
                composable("detail/{nomor}", arguments = listOf(navArgument("nomor") { type = NavType.IntType })) { DetailScreen(navController, vm, it.arguments?.getInt("nomor") ?: 1) }
            }
        }
    }
}

@Composable
fun CommonSearchBar(query: String, onSearch: (String) -> Unit, hint: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.padding(bottom = 1.dp)) { OutlinedTextField(value = query, onValueChange = onSearch, modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text(hint) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) }, shape = RoundedCornerShape(16.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface)) }
}

@Composable
fun QuranScreen(nav: androidx.navigation.NavController, vm: MainViewModel) {
    val surahs by vm.filteredSurahList.collectAsState(); val q by vm.quranQuery.collectAsState(); val load by vm.isLoading.collectAsState(); val str by vm.uiStrings.collectAsState(); val lang by vm.language.collectAsState()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CommonSearchBar(q, { vm.onSearchQuran(it) }, str.searchHint)
        if (load && surahs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else LazyColumn(Modifier.fillMaxSize()) { items(surahs) { s -> SurahItem(s, lang) { nav.navigate("detail/${s.number}") } } }
    }
}

@Composable
fun SurahItem(s: SurahSummary, lang: AppLanguage, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp, 6.dp).clickable { onClick() }, elevation = CardDefaults.cardElevation(0.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(s.number.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(s.name.transliteration.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(if(lang == AppLanguage.INDONESIA) s.name.translation.id else s.name.translation.en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f)) }
            Text(s.name.short, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun PrayerScreen(vm: MainViewModel) {
    val pt by vm.prayerTimes.collectAsState(); val loc by vm.locationName.collectAsState(); val coords by vm.userCoordinates.collectAsState(); val load by vm.isLoading.collectAsState(); val str by vm.uiStrings.collectAsState()
    val ctx = LocalContext.current; var showComp by remember { mutableStateOf(false) }
    val isLand = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val client = remember { LocationServices.getFusedLocationProviderClient(ctx) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.containsValue(true)) vm.fetchPrayerAndCity(ctx, 0.0, 0.0) }
    LaunchedEffect(Unit) { if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && pt == null) getCurrentLocation(ctx, client, vm) }
    if (showComp && coords != null) QiblaCompassDialog({ showComp = false }, coords!!.first, coords!!.second)

    var currentTimeMinutes by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while(true) { val c = Calendar.getInstance(); currentTimeMinutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE); delay(60000) } }
    val nextPrayerName = remember(pt, currentTimeMinutes) { pt?.let { timings -> fun parse(t: String) = try { val p = t.split(":"); p[0].toInt()*60 + p[1].toInt() } catch(e: Exception) { 0 };
        val times = listOf("Imsak" to timings.Imsak, "Subuh" to timings.Fajr, "Terbit" to timings.Sunrise, "Dzuhur" to timings.Dhuhr, "Ashar" to timings.Asr, "Maghrib" to timings.Maghrib, "Isya" to timings.Isha); val next = times.firstOrNull { parse(it.second.take(5)) > currentTimeMinutes }; next?.first ?: "Subuh" } ?: "" }

    val locContent = @Composable {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp))
                Text(if(loc.isEmpty()) str.waitingLocation else loc, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) launch.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) else getCurrentLocation(ctx, client, vm) }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(str.locateButton, fontSize = 12.sp, textAlign = TextAlign.Center) }
                    Button({ if(coords != null) showComp = true else Toast.makeText(ctx, "Update lokasi dulu!", Toast.LENGTH_SHORT).show() }, Modifier.weight(1f), enabled = coords != null, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Explore, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(str.qiblaButton, fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
            }
        }
    }
    val items = listOfNotNull(pt?.let{"Imsak" to it.Imsak}, pt?.let{"Subuh" to it.Fajr}, pt?.let{"Terbit" to it.Sunrise}, pt?.let{"Dzuhur" to it.Dhuhr}, pt?.let{"Ashar" to it.Asr}, pt?.let{"Maghrib" to it.Maghrib}, pt?.let{"Isya" to it.Isha})
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        if (load) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        else if (isLand) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.Center) { locContent() }; LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(0.6f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(items) { (n, t) -> PrayerItem(n, t, n == nextPrayerName) } } }
        else Column(horizontalAlignment = Alignment.CenterHorizontally) { locContent(); Spacer(Modifier.height(16.dp)); if (pt != null) LazyColumn { items(items) { (n, t) -> PrayerItem(n, t, n == nextPrayerName) }; item { Spacer(Modifier.height(16.dp)); Text("Sumber: Kemenag RI", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } } else Text("Silakan update lokasi", color = Color.Gray) }
    }
}

@Composable
fun AsmaulHusnaScreen(vm: MainViewModel) {
    val list by vm.filteredAsmaulHusna.collectAsState(); val q by vm.asmaulQuery.collectAsState(); val str by vm.uiStrings.collectAsState(); val ctx = LocalContext.current; val lang by vm.language.collectAsState()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { CommonSearchBar(q, { vm.onSearchAsmaul(it) }, str.searchAsmaul) }
            if (list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Memuat Asmaul Husna...", color = Color.Gray) }
            else LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list) { item ->
                    val meaning = if(lang == AppLanguage.INDONESIA) item.indo else item.en
                    Card(modifier = Modifier.clickable { val clip = ClipData.newPlainText("Asmaul Husna", "${item.arab}\n${item.latin}\n$meaning"); (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip); Toast.makeText(ctx, str.copied, Toast.LENGTH_SHORT).show() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) { Text(text = item.id.toString(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(item.arab, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp)); Text(item.latin, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Text(meaning, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoaScreen(vm: MainViewModel) {
    val list by vm.filteredDoaList.collectAsState(); val q by vm.doaQuery.collectAsState(); val ctx = LocalContext.current; val str by vm.uiStrings.collectAsState(); val lang by vm.language.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CommonSearchBar(q, { vm.onSearchDoa(it) }, str.searchDoa)
        if (list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Memuat Doa...", color = Color.Gray) }
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items = list, key = { it.id }) { doa ->
                var expanded by remember { mutableStateOf(false) }
                val title = if(lang == AppLanguage.INDONESIA) doa.judul else doa.judulEn
                val trans = if(lang == AppLanguage.INDONESIA) doa.terjemahan else doa.terjemahanEn
                Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(doa.id.toString(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }
                            Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color.Gray)
                        }
                        if (expanded) {
                            Spacer(Modifier.height(16.dp)); Divider(color = MaterialTheme.colorScheme.outline.copy(alpha=0.2f)); Spacer(Modifier.height(16.dp))
                            Text(doa.arab, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp))
                            Text(doa.latin, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp))
                            Text(trans, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { IconButton({ val clip = ClipData.newPlainText("Doa", "$title\n${doa.arab}\n\n${doa.latin}\n\n$trans"); (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip); Toast.makeText(ctx, str.copied, Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(20.dp)) } }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(ctx: Context, client: FusedLocationProviderClient, vm: MainViewModel) {
    client.lastLocation.addOnSuccessListener { loc -> if (loc != null) vm.fetchPrayerAndCity(ctx, loc.latitude, loc.longitude) else {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        client.requestLocationUpdates(req, object : LocationCallback() { override fun onLocationResult(res: LocationResult) { res.lastLocation?.let { vm.fetchPrayerAndCity(ctx, it.latitude, it.longitude); client.removeLocationUpdates(this) } } }, Looper.getMainLooper())
    }}.addOnFailureListener { Toast.makeText(ctx, "Gagal GPS", Toast.LENGTH_SHORT).show() }
}

@Composable
fun QiblaCompassDialog(dismiss: () -> Unit, lat: Double, long: Double) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    // Ambil WindowManager untuk cek rotasi layar
    val windowManager = remember { ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    val qibla = remember(lat, long) {
        Location("User").apply { latitude = lat; longitude = long }
            .bearingTo(Location("Kaaba").apply { latitude = 21.422487; longitude = 39.826206 })
    }

    var az by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        val acc = FloatArray(3); val mag = FloatArray(3)
        val rot = FloatArray(9); val outRot = FloatArray(9) // Matrix baru untuk hasil remap
        val ori = FloatArray(3)

        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null) return
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) System.arraycopy(e.values, 0, acc, 0, acc.size)
                else if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) System.arraycopy(e.values, 0, mag, 0, mag.size)

                if (SensorManager.getRotationMatrix(rot, null, acc, mag)) {
                    // --- LOGIC REMAP COORDINATE (FIX LANDSCAPE) ---
                    val rotation = windowManager.defaultDisplay.rotation
                    var axisX = SensorManager.AXIS_X
                    var axisY = SensorManager.AXIS_Y

                    when (rotation) {
                        Surface.ROTATION_90 -> { // Landscape Kiri
                            axisX = SensorManager.AXIS_Y
                            axisY = SensorManager.AXIS_MINUS_X
                        }
                        Surface.ROTATION_180 -> { // Terbalik
                            axisX = SensorManager.AXIS_MINUS_X
                            axisY = SensorManager.AXIS_MINUS_Y
                        }
                        Surface.ROTATION_270 -> { // Landscape Kanan
                            axisX = SensorManager.AXIS_MINUS_Y
                            axisY = SensorManager.AXIS_X
                        }
                    }

                    // Remap sumbu agar sesuai orientasi layar
                    SensorManager.remapCoordinateSystem(rot, axisX, axisY, outRot)
                    SensorManager.getOrientation(outRot, ori)

                    var d = Math.toDegrees(ori[0].toDouble()).toFloat()
                    if (d < 0) d += 360f
                    az = d
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }

    val animAz by animateFloatAsState(-az, tween(200), label="rot")

    Dialog(onDismissRequest = dismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Arah Kiblat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Box(contentAlignment = Alignment.Center) {
                    // Piringan Kompas
                    Canvas(Modifier.size(250.dp).rotate(animAz)) {
                        drawCircle(Color.Gray.copy(alpha=0.3f), size.minDimension/2, style = Stroke(4.dp.toPx()))
                        drawContext.canvas.nativeCanvas.apply {
                            drawText("N", center.x, center.y - size.minDimension/2 + 40.dp.toPx(), android.graphics.Paint().apply { color = android.graphics.Color.RED; textSize = 40.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true })
                        }
                        // Garis Mata Angin
                        drawLine(Color.Gray, start = Offset(center.x, center.y - size.minDimension/2), end = Offset(center.x, center.y - size.minDimension/2 + 20), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x, center.y + size.minDimension/2), end = Offset(center.x, center.y + size.minDimension/2 - 20), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x - size.minDimension/2, center.y), end = Offset(center.x - size.minDimension/2 + 20, center.y), strokeWidth = 5f)
                        drawLine(Color.Gray, start = Offset(center.x + size.minDimension/2, center.y), end = Offset(center.x + size.minDimension/2 - 20, center.y), strokeWidth = 5f)
                    }
                    // Jarum Kiblat
                    Canvas(Modifier.size(200.dp).rotate(animAz + qibla)) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(center.x, center.y - size.minDimension/2 + 20)
                            lineTo(center.x - 20, center.y)
                            lineTo(center.x + 20, center.y)
                            close()
                        }
                        drawPath(p, Color(0xFF10B981))
                        drawCircle(Color(0xFF10B981), 10f, center)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = dismiss) { Text("Tutup") }
            }
        }
    }
}

@Composable
fun PrayerItem(n: String, t: String, h: Boolean) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if(h) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, contentColor = if(h) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(n, style = MaterialTheme.typography.titleMedium, fontWeight = if(h) FontWeight.Bold else FontWeight.Medium); Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if(h) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(nav: androidx.navigation.NavController, vm: MainViewModel, n: Int) {
    val surahList by vm.loadedSurahs.collectAsState(); val l by vm.isLoading.collectAsState(); val str by vm.uiStrings.collectAsState(); val lang by vm.language.collectAsState(); val ctx = LocalContext.current
    LaunchedEffect(n) { if(surahList.isEmpty() || surahList.first().number != n) vm.openSurah(n) }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = {
                    val current = surahList.firstOrNull()
                    Column {
                        Text(current?.name?.transliteration?.id ?: str.loading, fontWeight = FontWeight.Bold)
                        if (current != null) Text(
                            if(lang==AppLanguage.INDONESIA) current.name.translation.id else current.name.translation.en,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha=0.8f)
                        )
                    }
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    }) { p ->
        Box(Modifier.padding(p).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (surahList.isEmpty() && l) { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
            else {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    surahList.forEach { surah ->
                        item {
                            Spacer(Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Surah Ke-${surah.number}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text(surah.name.transliteration.id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("${surah.revelation.id} • ${surah.verses.size} Ayat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha=0.7f))
                                    Spacer(Modifier.height(8.dp))
                                    if (surah.number != 1 && surah.number != 9) Text("بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيم", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        items(surah.verses) { a -> AyatItem(a, str, lang, ctx) }
                    }
                    item {
                        LaunchedEffect(Unit) { vm.loadNextSurah() }
                        if (l) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else if (surahList.lastOrNull()?.number == 114) Text("Khatam - Akhir Al-Quran", modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun AyatItem(a: Verse, str: UiStrings, lang: AppLanguage, ctx: Context) {
    val t = if (lang == AppLanguage.INDONESIA) a.translation.id else a.translation.en
    val juz = a.meta?.juz ?: 0
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(8.dp, 4.dp)) { Text("${str.verse} ${a.number.inSurah}", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    if(juz > 0) Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha=0.2f), shape = RoundedCornerShape(4.dp)) { Text("Juz $juz", modifier = Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                }
                IconButton({ val clip = ClipData.newPlainText("Quran", "QS ${a.number.inSurah}\n${a.text.arab}\n\n$t"); (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip); Toast.makeText(ctx, str.copied, Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(24.dp)); Text(a.text.arab, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End, lineHeight = 55.sp, fontFamily = FontFamily.Serif, fontSize = 32.sp), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(24.dp)); Text(a.text.transliteration.en, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp)); Text(t, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.8f)), lineHeight = 24.sp)
        }
    }
}