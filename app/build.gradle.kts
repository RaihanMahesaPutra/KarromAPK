plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // ✅ BENAR - tanpa version
}

android {
    namespace = "com.example.karrom2" // Sesuaikan dengan package Anda
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.karrom2"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    //composeOptions {
        // Versi compiler ini harus cocok dengan versi Kotlin di project-level
        // Kotlin 1.9.0 -> Compiler 1.5.1
        //kotlinCompilerExtensionVersion = "1.5.1"
    //}

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- CORE ANDROID ---
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // --- JETPACK COMPOSE (Bill of Materials / BOM) ---
    // Menggunakan BOM menjaga agar versi semua library compose sinkron
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // --- ICONS (PENTING UNTUK FITUR COPY & SEARCH) ---
    // Library ini diperlukan untuk Icons.Filled.ContentCopy, Search, dll.
    implementation("androidx.compose.material:material-icons-extended")

    // --- NAVIGATION ---
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- VIEWMODEL ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // --- RETROFIT (API) & GSON ---
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // --- DEBUGGING ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}