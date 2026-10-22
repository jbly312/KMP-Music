plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.example.videoplayerkmp.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.example.kmpmusic"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Дубликаты метаданных из зависимостей (ktor, kotlinx) — не нужны в APK
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/**")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.kotlinx.coroutinesAndroid)
    // Воспроизведение, фоновый сервис, уведомление и управление с экрана блокировки
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}
