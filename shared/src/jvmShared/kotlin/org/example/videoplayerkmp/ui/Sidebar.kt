package org.example.videoplayerkmp.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.videoplayerkmp.data.tracksCount

/**
 * Боковая панель: разделы и плейлисты. В узком окне сворачивается до полосы иконок
 * (их подписи — во всплывающих подсказках), кнопка вверху переключает режим вручную.
 */
@Composable
fun Sidebar(expanded: Boolean, onToggle: () -> Unit) {
    val navigator = LocalNavigator.current
    val dialogs = LocalDialogs.current
    val data = LocalLibraryData.current
    val colors = MaterialTheme.colorScheme
    val screen = navigator.screen
    val width by animateDpAsState(if (expanded) 236.dp else 68.dp, label = "sidebar")

    Column(Modifier.width(width).fillMaxHeight().background(colors.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Tip(if (expanded) "Свернуть боковую панель" else "Развернуть боковую панель") {
            IconButton(onClick = onToggle, modifier = Modifier.padding(start = 2.dp)) {
                Icon(
                    if (expanded) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                    contentDescription = if (expanded) "Свернуть боковую панель" else "Развернуть боковую панель",
                    tint = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        SidebarItem(expanded, "Главная", screen == Screen.Home, { navigator.go(Screen.Home) }) { selected ->
            SidebarIcon(Icons.Rounded.Home, selected)
        }
        SidebarItem(expanded, "Поиск", screen == Screen.Search, { navigator.go(Screen.Search) }) { selected ->
            SidebarIcon(Icons.Rounded.Search, selected)
        }
        SidebarItem(
            expanded, "Избранное", screen == Screen.Favorites, { navigator.go(Screen.Favorites) },
            trailing = data.favorites.size.takeIf { it > 0 }?.toString(),
        ) { selected -> SidebarIcon(Icons.Rounded.Favorite, selected) }
        SidebarItem(expanded, "История", screen == Screen.History, { navigator.go(Screen.History) }) { selected ->
            SidebarIcon(Icons.Rounded.History, selected)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 6.dp), color = colors.outlineVariant)

        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ПЛЕЙЛИСТЫ",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ImportButton { dialogs.show(DialogRequest.ImportSpotify) }
                NewPlaylistButton { dialogs.show(DialogRequest.NewPlaylist()) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                NewPlaylistButton { dialogs.show(DialogRequest.NewPlaylist()) }
                ImportButton { dialogs.show(DialogRequest.ImportSpotify) }
            }
        }

        if (data.playlists.isEmpty() && expanded) {
            Text(
                "Создайте плейлист кнопкой «+» или перенесите свой из Spotify",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(data.playlists, key = { it.id }) { playlist ->
                SidebarItem(
                    expanded = expanded,
                    label = playlist.name,
                    subtitle = tracksCount(playlist.tracks.size),
                    selected = (screen as? Screen.PlaylistView)?.id == playlist.id,
                    onClick = { navigator.go(Screen.PlaylistView(playlist.id)) },
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(7.dp))) {
                        PlaylistCover(playlist, Modifier.fillMaxSize())
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 6.dp), color = colors.outlineVariant)
        val account by LocalSync.current.account.collectAsState()
        SidebarItem(
            expanded = expanded,
            label = account?.user?.username ?: "Синхронизация",
            subtitle = if (account != null) syncSummary(short = true) else "Войти на свой сервер",
            selected = screen == Screen.Account,
            onClick = { navigator.go(Screen.Account) },
        ) { SyncIcon() }
    }
}

@Composable
private fun NewPlaylistButton(onClick: () -> Unit) {
    Tip("Новый плейлист") {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Add, "Новый плейлист", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportButton(onClick: () -> Unit) {
    Tip("Импорт из Spotify") {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Download, "Импорт из Spotify", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SidebarIcon(icon: ImageVector, selected: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SidebarItem(
    expanded: Boolean,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String? = null,
    leading: @Composable (selected: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Выбранный пункт отмечен подложкой (форма), а не только цветом иконки
    val background = when {
        selected -> colors.surfaceContainerHighest
        hovered -> colors.surfaceContainerHigh
        else -> Color.Transparent
    }
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (subtitle != null) 52.dp else 42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = if (expanded) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            leading(selected)
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (trailing != null) {
                    Text(trailing, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (expanded) row() else Tip(label) { row() }
}
