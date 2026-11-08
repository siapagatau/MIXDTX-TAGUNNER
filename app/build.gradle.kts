plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Dasar proyek = APK DTX (build system, tema, background, visual sentuhan, Skin Engine, SkinRepository).
// Isi "pad sampler" DTX sudah diganti engine MIXER (Oboe/C++: reverb, echo, EQ, gate, EQ global, floating, meter).
android {
    namespace = "com.kendang.realpads"
    compileSdk = 36
    // NDK r28+ otomatis meng-compile .so dengan alignment 16 KB (syarat Google Play), sama seperti APK DTX.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "mix.jos.android.lwbdgt"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.9.0"

        // Dibaca SkinManager (warisan DTX): skin selalu terbuka di APK mixer.
        buildConfigField("boolean", "IS_PREMIUM", "true")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("META-INF/*")
        // .so disimpan tidak terkompres + di-zipalign 16 KB oleh AGP (>= 8.5.1).
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // androidx.graphics:graphics-path <= 1.0.1 gagal cek RELRO 16 KB -> naikkan versi minimum (sama dengan APK DTX).
    constraints {
        implementation("androidx.graphics:graphics-path") {
            version { require("1.1.0") }
            because("16 KB page size: versi lama tidak lolos cek RELRO")
        }
    }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.oboe:oboe:1.9.3")
    // Skin Engine: renderer SVG untuk skin (skin/SkinRenderer.kt).
    implementation("com.caverock:androidsvg-aar:1.4")
}
