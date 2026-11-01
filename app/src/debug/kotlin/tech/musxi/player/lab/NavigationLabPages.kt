package tech.musxi.player.lab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.tabular

/** Shows one route of a tab. */
@Composable
internal fun LabPage(route: LabRoute, ctx: LabPageContext) {
    when (route) {
        is LabRoute.Root -> when (route.tab) {
            LabTab.LIBRARY -> LibraryRootPage(ctx, route)
            LabTab.SEARCH -> SearchRootPage(ctx, route)
            LabTab.PROFILE -> ProfileRootPage(ctx, route)
        }
        is LabRoute.Playlist -> PlaylistPage(ctx, route)
        is LabRoute.Artist -> ArtistPage(ctx, route)
        is LabRoute.SearchResult -> SearchResultPage(ctx, route)
        is LabRoute.Setting -> SettingPage(ctx, route)
    }
}

/** Page frame: an optional back bar over a list whose scroll position lives in the store. */
@Composable
private fun PageScaffold(
    ctx: LabPageContext,
    route: LabRoute,
    back: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val state = ctx.scroll.stateFor(ctx.nav.tab, route)
    Column(Modifier.fillMaxSize()) {
        if (back) DetailBar(onBack = ctx.onBack)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("lab-list"),
            contentPadding = PaddingValues(bottom = ctx.bottomPad + 16.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryRootPage(ctx: LabPageContext, route: LabRoute.Root) {
    val playlists = remember(ctx.env.songs) { labPlaylists(ctx.env.songs) }
    PageScaffold(ctx, route, back = false) {
        item(key = "title") { PageTitle("曲库", "${playlists.size} 个歌单") }
        items(playlists.chunked(2), key = { row -> row.first().index }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { playlist ->
                    PlaylistCard(playlist, Modifier.weight(1f)) { ctx.onPush(LabRoute.Playlist(playlist.index)) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: LabPlaylist, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) Motion.exit() else Motion.enter(),
        label = "cardPress",
    )
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // No clip: the press shows as a scale, and a rounded clip would cut the caption's first letter.
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Cover(playlist.seed, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large, playlist.coverUrl)
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistPage(ctx: LabPageContext, route: LabRoute.Playlist) {
    val playlist = remember(ctx.env.songs, route.index) { labPlaylists(ctx.env.songs).getOrNull(route.index) }
    PageScaffold(ctx, route, back = true) {
        if (playlist == null) return@PageScaffold
        item(key = "header") { PlaylistHeader(playlist) }
        itemsIndexed(playlist.songs, key = { i, song -> "$i-${song.id}" }) { i, song ->
            SongLine(
                song = song,
                leading = { IndexLabel(i + 1) },
                onArtist = { ctx.onPush(LabRoute.Artist(song.firstArtist())) },
            )
        }
    }
}

@Composable
private fun PlaylistHeader(playlist: LabPlaylist) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(playlist.seed, Modifier.size(220.dp), MaterialTheme.shapes.extraLarge, playlist.coverUrl)
        Spacer(Modifier.height(16.dp))
        Text(
            playlist.title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${playlist.subtitle} · ${playlist.minutes} 分钟",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun IndexLabel(number: Int) {
    Text(
        "$number",
        style = MaterialTheme.typography.bodyMedium.tabular,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(36.dp),
    )
}

@Composable
private fun CoverLeading(song: LabSong) {
    Cover(song.placeholderSeed(), Modifier.padding(end = 12.dp).size(44.dp), MaterialTheme.shapes.small, song.coverUrl)
}

/** One song row. Only the artist name is tappable, and only when [onArtist] is given. */
@Composable
private fun SongLine(
    song: LabSong,
    leading: @Composable () -> Unit,
    onArtist: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val artist = song.firstArtist()
                if (onArtist != null) {
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, color = cs.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button, onClick = onArtist)
                            .padding(vertical = 6.dp),
                    )
                } else {
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    " · ${song.album}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            labDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall.tabular,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArtistPage(ctx: LabPageContext, route: LabRoute.Artist) {
    val (own, more) = remember(ctx.env.songs, route.name) { artistSongs(ctx.env.songs, route.name) }
    PageScaffold(ctx, route, back = true) {
        item(key = "header") { ArtistHeader(route.name, own.size) }
        item(key = "own-label") { SectionLabel("热门歌曲") }
        itemsIndexed(own, key = { i, song -> "own-$i-${song.id}" }) { _, song ->
            SongLine(song, leading = { CoverLeading(song) })
        }
        item(key = "more-label") { SectionLabel("你可能也喜欢") }
        itemsIndexed(more, key = { i, song -> "more-$i-${song.id}" }) { _, song ->
            SongLine(song, leading = { CoverLeading(song) })
        }
    }
}

@Composable
private fun ArtistHeader(name: String, count: Int) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(name, Modifier.size(144.dp), CircleShape)
        Spacer(Modifier.height(14.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Text("$count 首歌曲", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A full-width text row with a press tile, shared by the search and profile lists. */
@Composable
private fun ListRow(
    text: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).pressableRow(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SearchRootPage(ctx: LabPageContext, route: LabRoute.Root) {
    val cs = MaterialTheme.colorScheme
    PageScaffold(ctx, route, back = false) {
        item(key = "title") { PageTitle("搜索") }
        item(key = "pill") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(44.dp)
                    .clip(CircleShape).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        item(key = "label") { SectionLabel("热门搜索") }
        itemsIndexed(HotKeywords, key = { _, word -> word }) { i, word ->
            ListRow(word, onClick = { ctx.onPush(LabRoute.SearchResult(word)) }, leading = {
                Text(
                    "${i + 1}", style = MaterialTheme.typography.bodyMedium.tabular,
                    color = if (i < 3) cs.primary else cs.onSurfaceVariant, modifier = Modifier.width(28.dp),
                )
            })
        }
    }
}

@Composable
private fun SearchResultPage(ctx: LabPageContext, route: LabRoute.SearchResult) {
    val results = remember(ctx.env.songs, route.keyword) { searchSongs(ctx.env.songs, route.keyword) }
    PageScaffold(ctx, route, back = true) {
        item(key = "title") { PageTitle(route.keyword, "${results.size} 个结果") }
        itemsIndexed(results, key = { i, song -> "$i-${song.id}" }) { _, song ->
            SongLine(song, leading = { CoverLeading(song) })
        }
    }
}

@Composable
private fun ProfileRootPage(ctx: LabPageContext, route: LabRoute.Root) {
    val cs = MaterialTheme.colorScheme
    PageScaffold(ctx, route, back = false) {
        item(key = "title") { PageTitle("我的") }
        item(key = "account") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover("profile", Modifier.size(64.dp), CircleShape)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Musxi 用户", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                    Text("示例账号 · 不联网", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        item(key = "label") { SectionLabel("设置") }
        items(ProfileRows, key = { it }) { row ->
            ListRow(row, onClick = { ctx.onPush(LabRoute.Setting(row)) })
        }
    }
}

@Composable
private fun SettingPage(ctx: LabPageContext, route: LabRoute.Setting) {
    val cs = MaterialTheme.colorScheme
    PageScaffold(ctx, route, back = true) {
        item(key = "title") { PageTitle(route.title) }
        items(SettingItems, key = { it.first }) { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
