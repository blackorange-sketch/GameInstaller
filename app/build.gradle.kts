plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.gameinstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.gameinstaller"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // libunarc.so це виконуваний файл unarc (збирається в CI через NDK і кладеться в jniLibs).
    // Щоб він з'явився в nativeLibraryDir і міг запускатися, бібліотеки мають розпаковуватися.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.tukaani:xz:1.9")              // LZMA для заголовка Inno
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    testImplementation("junit:junit:4.13.2")
}
