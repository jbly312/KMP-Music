import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Классификатор JavaFX: без него Gradle берёт пустые jar-ы без нативных библиотек
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    // Всё приложение (данные, YouTube, очередь, интерфейс) — в общем модуле
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    // Медиаклавиши (Fn + ⏯ ⏭ ⏮) — Java сама их не получает, нужен системный перехватчик клавиатуры
    implementation(libs.jnativehook)

    // Воспроизведение аудио — JavaFX Media с нативными библиотеками под текущую ОС
    listOf("base", "graphics", "media").forEach {
        implementation("org.openjfx:javafx-$it:${libs.versions.javafx.get()}:$javafxPlatform")
    }
}

compose.desktop {
    application {
        mainClass = "org.example.videoplayerkmp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KMP Music"
            // При выпуске новой версии увеличивайте номер — установщик обновит приложение поверх старого
            packageVersion = "1.1.2"
            description = "Music streaming player"
            vendor = "KMP Music"
            // jdk.httpserver — локальный прокси для потокового воспроизведения, java.logging — для JNativeHook
            modules("java.net.http", "jdk.httpserver", "jdk.unsupported", "java.naming", "java.logging")

            windows {
                iconFile.set(project.file("icons/app.ico"))
                menuGroup = "KMP Music"
                menu = true // ярлык в меню «Пуск»
                shortcut = true // ярлык на рабочем столе
                dirChooser = true
                // Установка в профиль пользователя — не нужны права администратора
                perUserInstall = true
                // Постоянный ID: по нему Windows понимает, что новая версия — обновление этой программы
                upgradeUuid = "2ace883d-fa58-4650-8045-a21fc88ffe81"
            }
            macOS {
                iconFile.set(project.file("icons/app.png"))
            }
            linux {
                iconFile.set(project.file("icons/app.png"))
            }
        }
    }
}
