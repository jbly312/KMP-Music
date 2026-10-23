package org.example.videoplayerkmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.videoplayerkmp.account.Account
import org.example.videoplayerkmp.account.SyncManager
import org.example.videoplayerkmp.account.describeError
import org.example.videoplayerkmp.account.normalizeServerUrl
import org.example.videoplayerkmp.sync.InviteDto
import org.example.videoplayerkmp.sync.ServerInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Аккаунт на сервере синхронизации: вход, состояние синхронизации, приглашения. */
@Composable
fun AccountScreen() {
    val sync = LocalSync.current
    val account by sync.account.collectAsState()
    val pad = if (LocalCompact.current) 16.dp else 32.dp
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = pad, vertical = if (LocalCompact.current) 16.dp else 28.dp),
        ) {
            Text("Синхронизация", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = 520.dp)) {
                val current = account
                if (current == null) SignIn(sync) else SignedIn(sync, current)
            }
        }
        ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
    }
}

/** Иконка состояния синхронизации — для боковой панели и заголовка «Библиотеки». */
@Composable
internal fun SyncIcon() {
    val sync = LocalSync.current
    val account by sync.account.collectAsState()
    val status by sync.status.collectAsState()
    val icon = when {
        account == null -> Icons.Rounded.CloudOff
        status.error != null -> Icons.Rounded.SyncProblem
        status.syncing -> Icons.Rounded.CloudSync
        else -> Icons.Rounded.CloudDone
    }
    val tint = if (account != null && status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(icon, "Синхронизация", Modifier.size(22.dp), tint = tint)
}

/** «Синхронизировано 5 мин назад», «Нет связи с сервером» и т. п.; [short] — для узкой боковой панели. */
@Composable
internal fun syncSummary(short: Boolean = false): String {
    val sync = LocalSync.current
    val status by sync.status.collectAsState()
    val pending by sync.pending.collectAsState()
    // Раз в полминуты обновляем «N мин назад»
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val last = status.lastSyncAt
    return when {
        status.syncing -> "Синхронизация…"
        // В узкой панели — только суть: «Сервер не найден» без подсказки после тире
        status.error != null -> if (short) status.error!!.substringBefore(" — ") else status.error!!
        pending > 0 -> (if (short) "Не отправлено: " else "Ждёт отправки: ") + pending
        last != null -> (if (short) "Обновлено " else "Синхронизировано ") + ago(maxOf(now, last) - last)
        else -> "Ещё не синхронизировано"
    }
}

private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        else -> "${minutes / 60} ч назад"
    }
}

// ---------- Вход ----------

private enum class Mode { LOGIN, REGISTER, SETUP }

@Composable
private fun SignIn(sync: SyncManager) {
    val scope = rememberCoroutineScope()
    val status by sync.status.collectAsState()
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<ServerInfo?>(null) }
    var mode by remember { mutableStateOf(Mode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Ошибка прошлой сессии (например, вход отозван) видна, пока не начали новую попытку
    var error by remember { mutableStateOf(status.error) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describeError(e)
            } finally {
                busy = false
            }
        }
    }

    Text(
        "Избранное, плейлисты и история станут общими на компьютере и телефоне. " +
            "Нужен свой сервер KMP Music — адрес и приглашение даст тот, кто его поднял.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    val info = server
    if (info == null) {
        fun connect() = run {
            val found = sync.serverInfo(url)
            url = normalizeServerUrl(url)
            mode = if (found.needsSetup) Mode.SETUP else Mode.LOGIN
            server = found
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Адрес сервера") },
            placeholder = { Text("music.example.com") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { connect() },
            enabled = url.isNotBlank() && !busy,
        ) { ButtonContent(busy, "Подключиться") }
    } else {
        ServerCard(info.name, url, onChange = { server = null; password = "" })
        Spacer(Modifier.height(16.dp))
        if (mode != Mode.SETUP) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(mode == Mode.LOGIN, { mode = Mode.LOGIN }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Вход") }
                SegmentedButton(mode == Mode.REGISTER, { mode = Mode.REGISTER }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("По приглашению") }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Text(
                "На сервере ещё нет аккаунтов. Первый станет администратором — он сможет приглашать других.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (mode == Mode.REGISTER) {
            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it },
                label = { Text("Код приглашения") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Имя пользователя") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            supportingText = if (mode != Mode.LOGIN) ({ Text("Не короче 8 символов") }) else null,
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        val ready = username.isNotBlank() && password.isNotEmpty() && (mode != Mode.REGISTER || invite.isNotBlank())
        Button(
            onClick = {
                run {
                    when (mode) {
                        Mode.LOGIN -> sync.login(url, username, password)
                        Mode.REGISTER -> sync.register(url, username, password, invite)
                        Mode.SETUP -> sync.setup(url, username, password)
                    }
                }
            },
            enabled = ready && !busy,
        ) {
            ButtonContent(busy, when (mode) {
                Mode.LOGIN -> "Войти"
                Mode.REGISTER -> "Создать аккаунт"
                Mode.SETUP -> "Создать аккаунт администратора"
            })
        }
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ButtonContent(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        Spacer(Modifier.width(10.dp))
    }
    Text(text)
}

@Composable
private fun ServerCard(name: String, url: String, onChange: (() -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onChange != null) TextButton(onClick = onChange) { Text("Изменить") }
        }
    }
}

// ---------- Вошли ----------

@Composable
private fun SignedIn(sync: SyncManager, account: Account) {
    val status by sync.status.collectAsState()
    val colors = MaterialTheme.colorScheme

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            Text(
                account.user.username.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(account.user.username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (account.user.isAdmin) Text("Администратор", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
    ServerCard(account.serverName, account.serverUrl)
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        SyncIcon()
        Spacer(Modifier.width(10.dp))
        Text(
            syncSummary(),
            style = MaterialTheme.typography.bodyLarge,
            color = if (status.error != null) colors.error else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = sync::syncNow, enabled = !status.syncing) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Синхронизировать")
        }
        OutlinedButton(onClick = sync::logout) {
            Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выйти")
        }
    }
    Text(
        "После выхода библиотека останется на этом устройстве.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (account.user.isAdmin) {
        Spacer(Modifier.height(28.dp))
        Invites(sync)
    }
}

@Composable
private fun Invites(sync: SyncManager) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notifier = LocalNotifier.current
    var invites by remember { mutableStateOf<List<InviteDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        invites = try {
            sync.invites()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
            emptyList()
        }
    }

    Text("Приглашения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        "Код действует 7 дней и подходит одному человеку. Передайте его вместе с адресом сервера.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    FilledTonalButton(
        onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val invite = sync.createInvite()
                    invites = listOf(invite) + invites.orEmpty()
                    clipboard.setText(AnnotatedString(invite.code))
                    notifier.notify("Код ${invite.code} скопирован")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = describeError(e)
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
    ) { ButtonContent(busy, "Создать приглашение") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

    val list = invites
    when {
        list == null -> CircularProgressIndicator(Modifier.padding(top = 16.dp).size(24.dp), strokeWidth = 2.dp)
        else -> list.forEach { invite ->
            InviteRow(invite, onCopy = {
                clipboard.setText(AnnotatedString(invite.code))
                notifier.notify("Код скопирован")
            })
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun InviteRow(invite: InviteDto, onCopy: () -> Unit) {
    val expired = invite.expiresAt < System.currentTimeMillis()
    val subtitle = when {
        invite.usedBy != null -> "Использовал ${invite.usedBy}"
        expired -> "Срок истёк"
        else -> "Действует до " + dateFormat.format(Instant.ofEpochMilli(invite.expiresAt).atZone(ZoneId.systemDefault()))
    }
    val active = invite.usedBy == null && !expired
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active) IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Скопировать код") }
    }
}
