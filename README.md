# KMP Music

Десктопный музыкальный плеер на Compose Multiplatform: поиск по YouTube Music,
очередь с автоплеем, плейлисты и избранное. Трек начинает играть через 1–2 секунды
и докачивается в фоне.

## Возможности

- **Поиск** песен (YouTube Music) и видео (YouTube) с подсказками и подгрузкой результатов;
  можно вставить ссылку на видео — трек сразу заиграет.
- **Очередь**: «играть следующим», «добавить в очередь», перемешивание, повтор очереди/трека.
- **Автоплей**: когда очередь заканчивается, добавляется «радио» YouTube Music по текущему треку.
- **Библиотека**: избранное, плейлисты, история — хранятся в `%APPDATA%\KMPMusic\library.json`.
- **Кэш** скачанных треков (до 1 ГБ, LRU) в `%LOCALAPPDATA%\KMPMusic\cache`;
  следующий трек в очереди скачивается заранее.
- Горячие клавиши: `Ctrl+Пробел` — пауза, `Ctrl+←/→` — треки, `Ctrl+F` — поиск.

## Как устроено воспроизведение

```
YouTube ──(InnerTube visionOS: ссылка на M4A, ~1 с)──┐
                                                     ▼
              AudioCache: 4 параллельных Range-запроса → файл на диске
                                                     │
              StreamServer (127.0.0.1): отдаёт байты по мере загрузки
                                                     ▼
                                   JavaFX MediaPlayer
```

- `desktopApp/src/main/kotlin/.../data` — модель трека, клиент YouTube (NewPipe + InnerTube), библиотека.
- `.../player` — загрузка и кэш, локальный стрим-сервер, `PlayerController` (очередь и воспроизведение).
- `.../ui` — экраны и компоненты Compose.

YouTube отдаёт аудио во фрагментированном MP4, который JavaFX не умеет перематывать по HTTP,
поэтому при перемотке плеер переключается на уже скачанный файл.

## Сервер синхронизации

Плейлисты, избранное и история синхронизируются между устройствами через собственный сервер
(Ktor + SQLite, в Docker). Аккаунты: первый — администратор, остальные — по приглашениям.
Как поднять свой сервер с Cloudflare Tunnel — в [docs/self-hosting.md](docs/self-hosting.md).

- `sync-protocol` — модели API, общие для сервера и приложений
- `server` — сам сервер; тесты: `./gradlew :server:test`

## Сборка

- Запуск: `./gradlew :desktopApp:run`
- Установщик: `./gradlew :desktopApp:packageMsi` (Windows), `packageDmg` (macOS), `packageDeb` (Linux)

---

Шаблон проекта Kotlin Multiplatform (Desktop / JVM):

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
      folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Desktop app:
    - Hot reload: `./gradlew :desktopApp:hotRun --auto`
    - Standard run: `./gradlew :desktopApp:run`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Desktop tests: `./gradlew :shared:jvmTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…