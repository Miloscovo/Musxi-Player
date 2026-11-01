package tech.musxi.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.lyrics.LyricsMatchState
import tech.musxi.core.lyrics.ParsedLyrics
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular

/** One search result. [key] is what preview and bind act on; it is never shown. */
data class LyricsCandidateUi(
    val key: MediaKey,
    val title: String,
    /** Artists, platform and album on one line. */
    val caption: String,
    val durationMs: Long,
    /** The stored manual match points at this candidate. */
    val bound: Boolean,
)

/**
 * The lyrics of the candidate being previewed. [lines] is empty while [loading] and when nothing
 * came back; [title] is empty when the candidate is not in the current result list.
 */
data class LyricsPreviewUi(
    val key: MediaKey,
    val title: String,
    val lines: List<String>,
    val loading: Boolean,
    val bound: Boolean,
    val canBind: Boolean,
)

/**
 * Everything the sheet needs. [original] is null when nothing is playing. [error] is already a
 * sentence for the user, never a backend code.
 */
data class LyricsMatchUi(
    val original: MediaKey? = null,
    val subtitle: String = "",
    /** "歌名 歌手": what the search field starts with. */
    val defaultQuery: String = "",
    val candidates: List<LyricsCandidateUi> = emptyList(),
    val preview: LyricsPreviewUi? = null,
    /** A manual match is stored for this song. */
    val manual: Boolean = false,
    val searching: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

private const val PREVIEW_LINES = 6
private const val MAX_QUERY = 200

/** Collapses the backend's match state plus the playing song's title and artist into the sheet. */
fun lyricsMatchUiOf(state: LyricsMatchState, title: String, artist: String): LyricsMatchUi {
    val original = state.originalKey
    // A binding that belongs to another song must not tick a row of this one.
    val binding = state.binding?.takeIf { it.originalKey == original }
    val boundKey = binding?.candidateKey
    val candidates = state.candidates.map { song ->
        LyricsCandidateUi(
            key = song.key,
            title = song.title,
            caption = lyricsCandidateCaption(song.artists, song.key.platform, song.album),
            durationMs = song.durationMs,
            bound = song.key == boundKey,
        )
    }
    val preview = state.previewKey?.let { key ->
        val lines = state.preview?.let { previewLinesOf(it) }.orEmpty()
        LyricsPreviewUi(
            key = key,
            title = candidates.firstOrNull { it.key == key }?.title.orEmpty(),
            lines = lines,
            loading = state.previewing,
            bound = key == boundKey,
            canBind = original != null && lines.isNotEmpty() && key != boundKey && !state.saving,
        )
    }
    return LyricsMatchUi(
        original = original,
        subtitle = lyricsSubtitleOf(title, artist),
        defaultQuery = lyricsDefaultQuery(title, artist),
        candidates = candidates,
        preview = preview,
        manual = binding != null,
        searching = state.searching,
        saving = state.saving,
        error = lyricsMatchErrorText(state.error, hasOriginal = original != null),
    )
}

/** The first rows of a candidate's lyrics: timed lines when there are any, else the plain text. */
internal fun previewLinesOf(lyrics: ParsedLyrics): List<String> {
    val timed = lyrics.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
    val source = timed.ifEmpty { lyrics.plainText.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    return source.take(PREVIEW_LINES)
}

/** Backend category to one short sentence. Null in, null out. */
internal fun lyricsMatchErrorText(code: String?, hasOriginal: Boolean): String? = when (code) {
    null -> null
    // The controller reports "no_original" when its target was dropped; with a song playing that
    // means the song's own account is not usable.
    "no_original" -> if (hasOriginal) "这首歌所在平台的账号未连接，请到「我的」重新连接" else "没有正在播放的歌曲"
    "account_unavailable", "account_changed", "expired", "permission" ->
        "该平台账号未连接或已过期，请到「我的」重新连接"
    "network", "http", "timeout", "unavailable" -> "网络异常，请稍后重试"
    "rate", "risk", "limit" -> "请求太频繁，稍后再试"
    "storage" -> "没能读取或保存匹配结果，请重试"
    else -> "没能完成，请重试"
}

internal fun lyricsSubtitleOf(title: String, artist: String): String = when {
    title.isBlank() -> ""
    artist.isBlank() -> "《$title》"
    else -> "《$title》· $artist"
}

/** "歌名 歌手", capped to what the backend accepts. */
internal fun lyricsDefaultQuery(title: String, artist: String): String =
    lyricsSearchKeywords("$title ${artist.replace(" / ", " ")}").orEmpty()

/** Null when there is nothing to search for; the backend rejects blank and over-long keywords. */
internal fun lyricsSearchKeywords(raw: String): String? =
    raw.trim().take(MAX_QUERY).trim().ifEmpty { null }

internal fun lyricsCandidateCaption(artists: List<String>, platform: Platform, album: String): String {
    val parts = mutableListOf<String>()
    artists.filter { it.isNotBlank() }.joinToString(" / ").takeIf { it.isNotEmpty() }?.let { parts += it }
    parts += platform.displayName
    if (album.isNotBlank() && !album.equals("<unknown>", ignoreCase = true)) parts += album
    return parts.joinToString(" · ")
}

/**
 * The one status line between the controls and the list. [searched] is whether the sheet has asked
 * for results since it opened; without it "nothing yet" and "nothing found" look the same.
 */
internal fun lyricsMatchNotice(ui: LyricsMatchUi, searched: Boolean): String? = when {
    ui.original == null -> ui.error ?: "没有正在播放的歌曲"
    ui.error != null -> ui.error
    ui.saving -> "正在保存…"
    ui.searching -> "正在搜索…"
    ui.candidates.isEmpty() && searched -> "没有找到相关歌曲，换个关键词试试"
    ui.candidates.isEmpty() -> "输入歌名或歌手，点键盘上的搜索"
    else -> null
}

/** What the preview card says when it has no lyric lines to show. */
internal fun lyricsPreviewNote(preview: LyricsPreviewUi, failed: Boolean): String? = when {
    preview.lines.isNotEmpty() -> null
    preview.loading -> "正在取歌词…"
    failed -> "没能取到这份歌词"
    else -> "这个版本没有歌词"
}

internal fun lyricsBindLabel(preview: LyricsPreviewUi, saving: Boolean): String = when {
    preview.bound -> "正在使用这份歌词"
    saving -> "正在保存…"
    else -> "用这个歌词"
}

private val MatchPlatforms = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)

/**
 * Manual lyrics matching: a bottom sheet over the player.
 *
 * Same shell as the queue panel (rounded top, `surfaceContainerHigh`, grab handle). The caller owns
 * the scrim and whether the sheet is composed at all. Opening it searches once for the playing song
 * on its own platform; the chips search the same words on another platform. Every callback carries
 * the song it was meant for, so a track change between the tap and the call cannot rebind the new one.
 */
@Composable
fun LyricsMatchSheet(
    ui: LyricsMatchUi,
    modifier: Modifier = Modifier,
    onSearch: (original: MediaKey, platform: Platform, keywords: String) -> Unit = { _, _, _ -> },
    onPreview: (candidate: MediaKey) -> Unit = {},
    onBind: (original: MediaKey, candidate: MediaKey) -> Unit = { _, _ -> },
    onUnbind: (original: MediaKey) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val original = ui.original
    var platform by remember(original) { mutableStateOf(original?.platform ?: Platform.NETEASE) }
    var query by remember(original) { mutableStateOf(ui.defaultQuery) }
    var searched by remember(original) { mutableStateOf(false) }
    fun search(on: Platform) {
        val song = original ?: return
        val keywords = lyricsSearchKeywords(query) ?: return
        searched = true
        onSearch(song, on, keywords)
    }
    LaunchedEffect(original) { search(platform) }

    Column(
        modifier.fillMaxWidth()
            .imePadding()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(cs.surfaceContainerHigh)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
        Header(ui, onDismiss)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (original != null) {
                SearchField(
                    query = query,
                    onChange = { query = it.take(MAX_QUERY) },
                    onSearch = {
                        keyboard?.hide()
                        if (!ui.saving) search(platform)
                    },
                    modifier = Modifier.padding(horizontal = Space.page),
                )
                Spacer(Modifier.height(10.dp))
                // Same switcher as the search and library pages, so the selected platform reads clearly.
                tech.musxi.player.ui.components.PlatformSegmented(
                    platforms = MatchPlatforms,
                    selected = platform,
                    onSelect = { picked ->
                        if (!ui.saving) {
                            platform = picked
                            search(picked)
                        }
                    },
                    modifier = Modifier.padding(horizontal = Space.page),
                )
                Spacer(Modifier.height(6.dp))
            }
            lyricsMatchNotice(ui, searched)?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.error != null) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp),
                )
            }
            if (ui.manual && original != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.page, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已手动匹配歌词",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onUnbind(original) },
                        enabled = !ui.saving,
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface),
                    ) {
                        Text("恢复自动匹配", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val preview = ui.preview
            // A stored match whose song is not in the current results still needs somewhere to show.
            if (preview != null && original != null && ui.candidates.none { it.key == preview.key }) {
                PreviewCard(
                    preview = preview,
                    saving = ui.saving,
                    failed = ui.error != null,
                    heading = preview.title.ifBlank { "当前匹配的歌词" },
                    onBind = { onBind(original, preview.key) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ui.candidates.forEach { candidate ->
                val shown = preview != null && preview.key == candidate.key
                CandidateRow(
                    candidate = candidate,
                    selected = shown,
                    onClick = {
                        // Tapping the row already on show only matters to retry an empty preview.
                        val retry = preview != null && preview.lines.isEmpty() && !preview.loading
                        if (!ui.saving && (!shown || retry)) onPreview(candidate.key)
                    },
                )
                if (shown && preview != null && original != null) {
                    PreviewCard(
                        preview = preview,
                        saving = ui.saving,
                        failed = ui.error != null,
                        heading = null,
                        onBind = { onBind(original, preview.key) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(ui: LyricsMatchUi, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("匹配歌词", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            if (ui.subtitle.isNotEmpty()) {
                Text(
                    ui.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
        ) {
            Text("关闭", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(44.dp).background(cs.surfaceContainerHighest, CircleShape),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "歌名、歌手",
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun CandidateRow(candidate: LyricsCandidateUi, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.onSurface.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                candidate.title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                candidate.caption,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (candidate.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(candidate.durationMs),
                style = MaterialTheme.typography.labelSmall.tabular,
                color = cs.onSurfaceVariant,
            )
        }
        if (candidate.bound) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, "正在使用", tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** The first lines of one candidate's lyrics, and the button that makes it the match. */
@Composable
private fun PreviewCard(
    preview: LyricsPreviewUi,
    saving: Boolean,
    failed: Boolean,
    heading: String?,
    onBind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(cs.onSurface.copy(alpha = 0.04f)).padding(14.dp),
    ) {
        if (heading != null) {
            Text(
                heading,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        val note = lyricsPreviewNote(preview, failed)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else {
            preview.lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onBind,
                enabled = preview.canBind,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(lyricsBindLabel(preview, saving), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
