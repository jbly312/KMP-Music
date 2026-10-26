plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Общий код приложения: данные, работа с YouTube, очередь и весь интерфейс на Compose.
// Платформенные части (воспроизведение, медиаклавиши, окно) — в desktopApp и androidApp.
kotlin {
    jvm()

    android {
        namespace = "org.example.videoplayerkmp.shared"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        // Обе платформы работают на JVM, поэтому общий код может пользоваться java.io, java.net и т. п.
        val jvmShared = create("jvmShared") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmShared)
        androidMain.get().dependsOn(jvmShared)

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            api(libs.compose.components.resources)
            api(libs.compose.materialIconsExtended)
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.newpipe.extractor)
            api(project(":sync-protocol"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutinesAndroid)
            implementation(libs.androidx.activityCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Синхронизация проверяется против настоящего сервера, запущенного прямо в тесте
        jvmTest.dependencies {
            implementation(project(":server"))
            implementation(libs.ktor.server.cio)
        }
    }
}
