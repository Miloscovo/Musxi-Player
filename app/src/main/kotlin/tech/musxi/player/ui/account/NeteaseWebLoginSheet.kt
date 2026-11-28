package tech.musxi.player.ui.account

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import tech.musxi.accounts.NeteaseWebLoginSession
import tech.musxi.accounts.NeteaseWebStage
import tech.musxi.accounts.NeteaseWebUiState
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.SheetDetent
import tech.musxi.player.ui.components.dialogDangerFill
import tech.musxi.player.ui.components.isLargeFont
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.MusxiShapes
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.identityColor
import tech.musxi.player.ui.theme.rememberHaptics

internal const val NeteaseWebSubmitTag = "netease-web-submit"
internal const val NeteaseWebReloadTag = "netease-web-reload"
internal const val NeteaseWebCloseTag = "netease-web-close"
internal const val NeteaseWebNoticeTag = "netease-web-notice"
internal const val NeteaseWebCardTag = "netease-web-card"
internal const val NeteaseWebFooterTag = "netease-web-footer"

private val NoticeCardShape = RoundedCornerShape(13.dp)

internal data class NeteaseWebNoticeUi(
    val message: String,
    val isError: Boolean,
    val offerScanner: Boolean = false,
)

internal fun neteaseWebNoticeOf(state: NeteaseWebUiState): NeteaseWebNoticeUi? = when (state.stage) {
    NeteaseWebStage.PREPARING,
    NeteaseWebStage.LOADING,
    NeteaseWebStage.READY -> null
    NeteaseWebStage.COOKIE_UNAVAILABLE -> NeteaseWebNoticeUi(
        message = "暂时无法读取网页登录状态，请关闭后重试，或改用相册扫码登录。",
        isError = true,
        offerScanner = true,
    )
    NeteaseWebStage.CLEANUP_FAILED -> NeteaseWebNoticeUi(
        message = "上次登录页面尚未关闭，请稍后重新打开。",
        isError = true,
    )
    NeteaseWebStage.NAVIGATION_BLOCKED -> NeteaseWebNoticeUi(
        message = "当前页面无法打开该链接，请返回官方登录页，或改用相册扫码登录。",
        isError = false,
        offerScanner = true,
    )
    NeteaseWebStage.SSL_ERROR -> NeteaseWebNoticeUi(
        message = "安全连接未通过，请检查网络和系统时间后点击右上角刷新。",
        isError = true,
    )
    NeteaseWebStage.LOAD_ERROR -> NeteaseWebNoticeUi(
        message = "官方登录页加载未完成，请检查网络后点击右上角刷新。",
        isError = true,
    )
    NeteaseWebStage.COOKIES_MISSING -> NeteaseWebNoticeUi(
        message = "还没有检测到登录完成，请先在上方网页中登录，再点击验证。",
        isError = true,
    )
}

internal fun acquireSecureFlag(window: Window): Boolean = secureFlagWasAdded(window.attributes.flags).also { added ->
    if (added) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun secureFlagWasAdded(flags: Int): Boolean =
    flags and WindowManager.LayoutParams.FLAG_SECURE == 0

internal fun restoreSecureFlag(window: Window, addedByLogin: Boolean) {
    if (addedByLogin) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

@Composable
internal fun NeteaseSecureWindowEffect(window: Window?) {
    DisposableEffect(window) {
        val addedByLogin = window?.let(::acquireSecureFlag) == true
        onDispose {
            if (window != null) restoreSecureFlag(window, addedByLogin)
        }
    }
}

/**
 * Hosts [session] inside [MusxiSheet] in `MainShell`'s `GlassHost` overlay so it inherits
 * Musxi's liquid glass / solid fallback, drag-to-dismiss physics, and haptics, while temporarily
 * protecting the Activity window with `FLAG_SECURE`. Cookie values never reach UI callbacks.
 */
@Composable
fun NeteaseWebLoginSheetHost(
    session: NeteaseWebLoginSession?,
    activity: Activity?,
    dark: Boolean,
    onDismiss: () -> Unit,
    onUseScanner: (() -> Unit)? = null,
) {
    val window = activity?.window
    val last = remember { arrayOfNulls<NeteaseWebLoginSession>(1) }
    if (session != null) last[0] = session
    val active = session ?: last[0]
    var uiState by remember(active) { mutableStateOf(NeteaseWebUiState()) }

    MusxiSheet(
        visible = session != null,
        onDismiss = onDismiss,
        initialDetent = SheetDetent.FULL,
        onDetentChange = { if (it == SheetDetent.MEDIUM) onDismiss() },
    ) {
        if (active == null) return@MusxiSheet
        NeteaseSecureWindowEffect(window)
        DisposableEffect(Unit) { onDispose { last[0] = null } }
        BackHandler(enabled = session != null && uiState.canGoBack) {
            if (!active.goBack()) onDismiss()
        }
        NeteaseWebLoginSheetBody(
            state = uiState,
            dark = dark,
            onClose = onDismiss,
            onReload = { active.reload() },
            onSubmit = { active.submit() },
            onUseScanner = onUseScanner,
        ) {
            if (activity != null) {
                key(active) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).also { slot ->
                                active.attach(activity, slot) { uiState = it }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Sheet body for NetEase web login (placed under [MusxiSheet]'s grab handle).
 * Shared by [NeteaseWebLoginSheetHost] in production and by design sheets / UI tests on the JVM.
 */
@Composable
fun ColumnScope.NeteaseWebLoginSheetBody(
    state: NeteaseWebUiState,
    dark: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onUseScanner: (() -> Unit)? = null,
    webContent: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val density = LocalDensity.current
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    val largeFont = isLargeFont()
    val notice = neteaseWebNoticeOf(state)
    val showProgress = state.stage == NeteaseWebStage.PREPARING ||
        (state.stage == NeteaseWebStage.LOADING && state.progress in 1..99)
    val progressFraction = when {
        state.stage == NeteaseWebStage.PREPARING -> 0.15f
        state.stage == NeteaseWebStage.LOADING -> (state.progress / 100f).coerceIn(0.08f, 0.98f)
        else -> 0f
    }

    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxWidth()
            .semantics { paneTitle = "网易云网页登录" }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .imePadding(),
    ) {
        // 52dp compact header below MusxiSheet's grab handle: close · dot + title · reload.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag(NeteaseWebCloseTag),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Platform.NETEASE.identityColor(dark)),
                    )
                    Text(
                        text = "网易云网页登录",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                val subtitle = when (state.stage) {
                    NeteaseWebStage.PREPARING -> "正在准备安全环境…"
                    NeteaseWebStage.LOADING -> state.host?.let { "正在加载 $it…" } ?: "正在加载官方登录页…"
                    else -> state.host ?: "y.music.163.com"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onReload,
                enabled = state.canReload,
                modifier = Modifier.size(48.dp).testTag(NeteaseWebReloadTag),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "重新加载",
                    tint = if (state.canReload) cs.onSurface else cs.onSurface.copy(alpha = 0.32f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Contextual notice banner, shown only when attention or fallback is needed.
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.standard()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.standard()),
        ) {
            if (notice != null) {
                val danger = dialogDangerFill(dark)
                val bgColor = if (notice.isError) {
                    danger.copy(alpha = if (dark) 0.16f else 0.09f)
                } else {
                    cs.onSurface.copy(alpha = if (dark) 0.08f else 0.05f)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.sibling, vertical = 4.dp)
                        .clip(NoticeCardShape)
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag(NeteaseWebNoticeTag),
                ) {
                    val errorText = if (dark) Color(0xFFFF8E97) else danger
                    Text(
                        text = notice.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice.isError) errorText else cs.onSurfaceVariant,
                        maxLines = ALL_LINES,
                    )
                    if (notice.offerScanner && onUseScanner != null) {
                        Spacer(Modifier.height(2.dp))
                        TextButton(
                            onClick = onUseScanner,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(min = 36.dp),
                        ) {
                            Text(
                                text = "改用相册扫码登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.primary,
                            )
                        }
                    }
                }
            }
        }

        // Official light web page framed uniformly in a Musxi large rounded white card.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(horizontal = Space.sibling, vertical = 4.dp)
                .clip(MusxiShapes.large)
                .background(Color.White)
                .border(
                    width = 0.5.dp,
                    color = if (dark) Color.White.copy(alpha = 0.12f) else cs.outlineVariant.copy(alpha = 0.6f),
                    shape = MusxiShapes.large,
                )
                .testTag(NeteaseWebCardTag),
        ) {
            webContent()
            if (showProgress) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(cs.outlineVariant.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(cs.primary),
                    )
                }
            }
        }

        // Bottom dedicated action bar: note above the accent capsule button (aligned with UpdateSheet).
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page)
                .padding(top = 10.dp, bottom = 10.dp)
                .testTag(NeteaseWebFooterTag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!imeOpen && !largeFont) {
                Text(
                    text = "请在上方完成登录后点击验证，凭据仅保存在本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = ALL_LINES,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
            Button(
                onClick = {
                    haptic(Haptic.TICK)
                    onSubmit()
                },
                enabled = state.canSubmit,
                shape = if (largeFont) RoundedCornerShape(22.dp) else CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                    disabledContainerColor = cs.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(NeteaseWebSubmitTag),
            ) {
                Text(
                    text = "完成登录并验证",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = if (largeFont) ALL_LINES else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
