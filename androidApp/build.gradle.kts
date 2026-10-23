import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * Ключ подписи релиза — в keystore.properties в корне проекта (не в git):
 * storeFile, storePassword, keyAlias, keyPassword. Обновление на телефоне ставится,
 * только если подписано тем же ключом — его нельзя терять.
 */
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
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

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // NewPipe и Rhino работают через рефлексию — без сжатия надёжнее
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
