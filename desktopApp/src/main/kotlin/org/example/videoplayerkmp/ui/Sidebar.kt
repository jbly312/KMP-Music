package org.example.videoplayerkmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.videoplayerkmp.data.tracksCount

@Composable
fun Sidebar() {
    val navigator = LocalNavigator.current
    val dialogs = LocalDialogs.current
    val data = LocalLibraryData.current
    val colors = MaterialTheme.colorScheme
    val screen = navigator.screen

    Column(Modifier.width(248.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(12.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(colors.primary, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("KMP Music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        NavItem(Icons.Rounded.Home, "Главная", screen == Screen.Home) { navigator.go(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Поиск", screen == Screen.Search) { navigator.go(Screen.Search) }
        NavItem(
            Icons.Rounded.Favorite, "Избранное", screen == Screen.Favorites,
            badge = data.favorites.size.takeIf { it > 0 }?.toString(),
        ) { navigator.go(Screen.Favorites) }
        NavItem(Icons.Rounded.History, "История", screen == Screen.History) { navigator.go(Screen.History) }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ПЛЕЙЛИСТЫ",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Tip("Новый плейлист") {
                IconButton(onClick = { dialogs.show(DialogRequest.NewPlaylist()) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Add, "Новый плейлист", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
        }

        if (data.playlists.isEmpty()) {
            Text(
                "Создайте плейлист кнопкой «+» или через меню трека",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(data.playlists, key = { it.id }) { playlist ->
                val selected = (screen as? Screen.PlaylistView)?.id == playlist.id
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                tracksCount(playlist.tracks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    },
                    icon = {
                        Thumbnail(
                            playlist.tracks.firstOrNull()?.thumbnailUrl,
                            Modifier.size(36.dp),
                            RoundedCornerShape(6.dp),
                        )
                    },
                    selected = selected,
                    onClick = { navigator.go(Screen.PlaylistView(playlist.id)) },
                    modifier = Modifier.height(54.dp),
                    colors = navColors(),
                )
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        icon = { Icon(icon, null) },
        badge = badge?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(46.dp),
        colors = navColors(),
    )
}

@Composable
private fun navColors() = NavigationDrawerItemDefaults.colors(
    unselectedContainerColor = Color.Transparent,
    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
