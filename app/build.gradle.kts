plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.devicelock2"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.devicelock"
        minSdk = 29
        targetSdk = 35
        // 업데이트 설치는 versionCode 가 커야 한다. CI 는 실행 번호(github.run_number)를 넘긴다.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.2.0"
    }

    // 업데이트 설치가 되려면 매번 같은 키로 서명해야 한다. CI 는 GitHub Secrets 의 키스토어를 KEYSTORE_FILE 등 환경 변수로 넘긴다.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() && File(it).exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = File(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 키스토어가 주어지면 그 키로 서명한다. 없으면(로컬 개발) 디버그 키로 서명한다.
            // 주의: 디버그 키는 빌드 환경마다 달라 그 APK 로는 업데이트 설치가 되지 않는다.
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
