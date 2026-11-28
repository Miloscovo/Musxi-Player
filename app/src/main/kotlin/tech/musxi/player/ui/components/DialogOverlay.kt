package tech.musxi.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.MusxiShapes
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.isDarkSurface
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tabular

/**
 * The shell's same-window dialog layer (docs/design/interaction-spec.md, section 9): dialogs opened
 * from pages are drawn here, in [GlassHost]'s overlay, so their card is real [GlassStyle.REGULAR]
 * glass over the page and covers the dock. Outside a shell (tests, or dialogs over a sheet),
 * [MusxiDialog] falls back to drawing in place with the same geometry and solid card.
 */
val LocalDialogOverlay = staticCompositionLocalOf<DialogOverlayState?> { null }

/** Dialogs currently mounted in the shell's overlay, newest last. */
@Stable
class DialogOverlayState {
    internal val entries = mutableStateListOf<DialogEntry>()

    internal fun show(entry: DialogEntry) {
        if (entry !in entries) entries.add(entry)
        entry.mounted = true
    }

    internal fun remove(entry: DialogEntry) {
        entries.remove(entry)
        entry.mounted = false
    }

    /** True while at least one dialog is visible: the shell's own back handling waits behind it. */
    val open: Boolean get() = entries.any { it.visible }
}

@Stable
internal class DialogEntry {
    var visible by mutableStateOf(false)
    var mounted by mutableStateOf(false)
    var dismissible by mutableStateOf(true)
    var forceSolid by mutableStateOf(false)
    var paneLabel by mutableStateOf("对话框")
    var onDismiss: () -> Unit by mutableStateOf({})
    var cardContent: @Composable ColumnScope.() -> Unit by mutableStateOf({})
}

/**
 * Draws the dialogs of [state]. Place it last in [GlassHost]'s overlay (after [MenuOverlayLayer])
 * so every page dialog sits above menus, the dock and sheets, and takes back presses first.
 */
@Composable
fun DialogOverlayLayer(state: DialogOverlayState) {
    if (state.entries.isEmpty()) return
    for (entry in state.entries.toList()) {
        key(entry) {
            DialogBackdropAndCard(
                visible = entry.visible,
                dismissible = entry.dismissible,
                forceSolid = entry.forceSolid,
                paneLabel = entry.paneLabel,
                onDismiss = entry.onDismiss,
                onGone = { state.remove(entry) },
                cardContent = entry.cardContent,
            )
        }
    }
}

/** Button roles for [MusxiDialogButton]. Large areas stay neutral; colour marks the one decision. */
enum class DialogButtonTone { PRIMARY, SECONDARY, DANGER }

/**
 * Scope for dialog action buttons: side-by-side equal width at normal sizes, stacked full-width
 * from [LARGE_FONT_SCALE] onward so 200% font scale and busy labels never clip.
 */
interface DialogActionsScope {
    fun Modifier.dialogActionWidth(): Modifier
}

private val DialogNoteShape = RoundedCornerShape(13.dp)
private val DialogFieldShape = RoundedCornerShape(16.dp)
private val DialogLargeButtonShape = RoundedCornerShape(22.dp)
private const val DialogScrimAlpha = 0.25f
private const val SolidDialogScrimAlpha = 0.34f

internal fun dialogDangerFill(dark: Boolean): Color =
    if (dark) Color(0xFFE05A66) else Color(0xFFC63D49)

/**
 * Musxi's unified floating dialog card (spec section 9).
 *
 * - Drawn in the same window as [GlassStyle.REGULAR] glass over pages (via [LocalDialogOverlay] or
 *   directly inside [GlassHost]'s overlay).
 * - Automatically falls back to the theme's solid surface (white in light, `surfaceContainerHigh`
 *   in dark) when transparency is off, on battery saver / Android < 12, in tests, or when
 *   [forceSolid] is true (e.g., dialogs opened above a glass [MusxiSheet], to avoid double blur).
 * - Handles the scrim (black α 0.25, swallows touches), back button, IME avoidance, 200% font
 *   scrolling, and accessibility pane semantics in one place.
 */
@Composable
fun MusxiDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    dismissible: Boolean = true,
    forceSolid: Boolean = false,
    eyebrow: String? = null,
    eyebrowDot: Color? = null,
    body: String? = null,
    note: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable DialogActionsScope.() -> Unit,
) {
    val overlay = if (forceSolid) null else LocalDialogOverlay.current
    val cardBody: @Composable ColumnScope.() -> Unit = {
        DialogCardLayout(
            title = title,
            eyebrow = eyebrow,
            eyebrowDot = eyebrowDot,
            body = body,
            note = note,
            content = content,
            actions = actions,
        )
    }
    if (overlay != null) {
        val entry = remember { DialogEntry() }
        LaunchedEffect(visible) {
            if (visible) overlay.show(entry)
        }
        DisposableEffect(overlay, entry) {
            onDispose { overlay.remove(entry) }
        }
        if (!visible && !entry.mounted) return
        SideEffect {
            entry.visible = visible
            entry.dismissible = dismissible
            entry.forceSolid = forceSolid
            entry.paneLabel = title.ifBlank { "对话框" }
            entry.onDismiss = onDismiss
            entry.cardContent = cardBody
        }
        return
    }

    var mounted by remember { mutableStateOf(visible) }
    if (visible) mounted = true
    if (!visible && !mounted) return
    DialogBackdropAndCard(
        visible = visible,
        dismissible = dismissible,
        forceSolid = forceSolid,
        paneLabel = title.ifBlank { "对话框" },
        onDismiss = onDismiss,
        onGone = { mounted = false },
        cardContent = cardBody,
    )
}

@Composable
private fun DialogBackdropAndCard(
    visible: Boolean,
    dismissible: Boolean,
    forceSolid: Boolean,
    paneLabel: String,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    cardContent: @Composable ColumnScope.() -> Unit,
) {
    val scale = remember { Animatable(0.92f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            coroutineScope {
                launch { scale.animateTo(1f, Motion.menu()) }
                launch { alpha.animateTo(1f, Motion.enter()) }
            }
        } else {
            coroutineScope {
                launch { scale.animateTo(0.92f, Motion.menuDismiss()) }
                launch { alpha.animateTo(0f, Motion.exit()) }
            }
            onGone()
        }
    }
    // Always intercept back while the dialog is mounted (including exit animation or busy state)
    // so a press never leaks to the page or player behind it.
    BackHandler { if (visible && dismissible) onDismiss() }

    val cs = MaterialTheme.colorScheme
    val glass = dialogGlass(forceSolid)
    val scrimMax = if (forceSolid) SolidDialogScrimAlpha else DialogScrimAlpha
    val curVisible by rememberUpdatedState(visible)
    val curDismissible by rememberUpdatedState(dismissible)
    val curOnDismiss by rememberUpdatedState(onDismiss)
    Box(Modifier.fillMaxSize()) {
        // Scrim: absorbs every touch sequence through pointer-up so taps and drags never leak
        // to the page below, even when dismissing flips `visible` mid-gesture.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = scrimMax * alpha.value)) }
                .pointerInput(Unit) {
                    val slopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downPos = down.position
                        var moved = false
                        var pointers = 1
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.id == down.id) {
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    if (dx * dx + dy * dy > slopSq) moved = true
                                }
                                change.consume()
                            }
                            pointers = maxOf(pointers, event.changes.size)
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved && pointers == 1 && curVisible && curDismissible) curOnDismiss()
                    }
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 28.dp, vertical = Space.page),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                Column(
                    Modifier
                        .semantics {
                            dialog()
                            paneTitle = paneLabel
                            isTraversalGroup = true
                        }
                        .fillMaxWidth()
                        .widthIn(max = 312.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            this.alpha = alpha.value
                        }
                        .glass(glass, MusxiShapes.extraLarge, GlassStyle.REGULAR)
                        // Swallows taps on the card itself so they never reach the scrim behind it.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .verticalScroll(rememberScrollState())
                        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
                    content = cardContent,
                )
            }
        }
    }
}

@Composable
private fun dialogGlass(forceSolid: Boolean): GlassScope {
    val host = if (forceSolid) null else LocalGlass.current
    if (host != null) return host
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val haze = remember { HazeState() }
    return GlassScope(
        haze = haze,
        blurs = false,
        dark = dark,
        solid = if (dark) cs.surfaceContainerHigh else Color.White,
    )
}

@Composable
private fun ColumnScope.DialogCardLayout(
    title: String,
    eyebrow: String?,
    eyebrowDot: Color?,
    body: String?,
    note: String?,
    content: (@Composable ColumnScope.() -> Unit)?,
    actions: @Composable DialogActionsScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    if (!eyebrow.isNullOrBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            if (eyebrowDot != null) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(eyebrowDot))
            }
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = listLines(),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = cs.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    if (!body.isNullOrBlank()) {
        Spacer(Modifier.height(9.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
    }
    if (!note.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(DialogNoteShape)
                .background(cs.onSurface.copy(alpha = if (dark) 0.07f else 0.045f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
    if (content != null) {
        Spacer(Modifier.height(14.dp))
        content()
    }
    Spacer(Modifier.height(18.dp))
    if (isLargeFont()) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val scope = remember {
                object : DialogActionsScope {
                    override fun Modifier.dialogActionWidth(): Modifier = fillMaxWidth()
                }
            }
            scope.actions()
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rowScope = this
            val scope = remember(rowScope) {
                object : DialogActionsScope {
                    override fun Modifier.dialogActionWidth(): Modifier = with(rowScope) { weight(1f) }
                }
            }
            scope.actions()
        }
    }
}

/**
 * Capsule dialog button (44dp min height, scales cleanly at 200% font size).
 * Put two of these inside [MusxiDialog]'s `actions` using `Modifier.dialogActionWidth()`.
 */
@Composable
fun MusxiDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: DialogButtonTone = DialogButtonTone.SECONDARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    interactive: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val haptic = rememberHaptics()
    val largeFont = isLargeFont()
    val (bg, fg) = when (tone) {
        DialogButtonTone.PRIMARY -> cs.primary to cs.onPrimary
        DialogButtonTone.DANGER -> dialogDangerFill(dark) to Color.White
        DialogButtonTone.SECONDARY ->
            (if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.065f)) to cs.onSurface
    }
    val clickableEnabled = interactive && enabled && !busy
    val shape = if (largeFont) DialogLargeButtonShape else CircleShape
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .alpha(if (enabled || busy) 1f else 0.42f)
            .background(bg)
            .clickable(
                enabled = clickableEnabled,
                role = Role.Button,
                onClick = {
                    if (tone != DialogButtonTone.SECONDARY) haptic(Haptic.TICK)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                ProgressArc(color = fg, size = 14.dp, strokeWidth = 1.6.dp)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                textAlign = TextAlign.Center,
                maxLines = if (largeFont) ALL_LINES else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Standard two-button confirmation dialog. Use [danger] for destructive actions (delete, sign out,
 * clear cache). While [busy] is true, dismissal and both buttons are locked.
 */
@Composable
fun MusxiConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissLabel: String = "取消",
    danger: Boolean = false,
    busy: Boolean = false,
    busyLabel: String = confirmLabel,
    forceSolid: Boolean = false,
    eyebrow: String? = null,
    eyebrowDot: Color? = null,
    note: String? = null,
) {
    MusxiDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        dismissible = !busy,
        forceSolid = forceSolid,
        eyebrow = eyebrow,
        eyebrowDot = eyebrowDot,
        body = body,
        note = note,
    ) {
        MusxiDialogButton(
            label = dismissLabel,
            onClick = { if (visible) onDismiss() },
            modifier = Modifier.dialogActionWidth(),
            tone = DialogButtonTone.SECONDARY,
            enabled = !busy,
            interactive = visible,
        )
        MusxiDialogButton(
            label = if (busy) busyLabel else confirmLabel,
            onClick = { if (visible) onConfirm() },
            modifier = Modifier.dialogActionWidth(),
            tone = if (danger) DialogButtonTone.DANGER else DialogButtonTone.PRIMARY,
            enabled = !busy,
            busy = busy,
            interactive = visible,
        )
    }
}

/**
 * Standard single-line naming / input dialog. Uses [MusxiDialogTextField], auto-focuses on open,
 * handles IME Done submission, and locks dismissal while [busy] is true.
 */
@Composable
fun MusxiTextFieldDialog(
    visible: Boolean,
    title: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "取消",
    placeholder: String = "",
    helperText: String? = null,
    errorText: String? = null,
    counterText: String? = null,
    busy: Boolean = false,
    busyLabel: String = confirmLabel,
    forceSolid: Boolean = false,
    eyebrow: String? = null,
    eyebrowDot: Color? = null,
    auxiliaryLabel: String? = null,
    onAuxiliary: (() -> Unit)? = null,
    autoFocus: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        if (!visible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    MusxiDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        dismissible = !busy,
        forceSolid = forceSolid,
        eyebrow = eyebrow,
        eyebrowDot = eyebrowDot,
        content = {
            MusxiDialogTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = visible && !busy,
                placeholder = placeholder,
                helperText = helperText,
                errorText = errorText,
                counterText = counterText,
                autoFocus = autoFocus && visible,
                onDone = { if (visible && !busy && confirmEnabled) onConfirm() },
            )
            if (auxiliaryLabel != null && onAuxiliary != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { if (visible) onAuxiliary() },
                    enabled = visible && !busy,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        auxiliaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
        MusxiDialogButton(
            label = dismissLabel,
            onClick = { if (visible) onDismiss() },
            modifier = Modifier.dialogActionWidth(),
            tone = DialogButtonTone.SECONDARY,
            enabled = !busy,
            interactive = visible,
        )
        MusxiDialogButton(
            label = if (busy) busyLabel else confirmLabel,
            onClick = { if (visible) onConfirm() },
            modifier = Modifier.dialogActionWidth(),
            tone = DialogButtonTone.PRIMARY,
            enabled = confirmEnabled && !busy,
            busy = busy,
            interactive = visible,
        )
    }
}

/**
 * Musxi's capsule text field for dialogs (16dp radius, inline clear button, accent focus ring,
 * helper/error + optional character counter row). Usable standalone in unit tests.
 */
@Composable
fun MusxiDialogTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    helperText: String? = null,
    errorText: String? = null,
    counterText: String? = null,
    autoFocus: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val isError = !errorText.isNullOrBlank()
    val errorColor = if (dark) Color(0xFFFF8E97) else dialogDangerFill(false)

    val containerColor = when {
        dark && focused -> Color.White.copy(alpha = 0.12f)
        dark -> Color.White.copy(alpha = 0.09f)
        focused -> Color.White.copy(alpha = 0.88f)
        else -> Color.Black.copy(alpha = 0.055f)
    }
    val borderColor = when {
        isError -> errorColor
        focused -> cs.primary
        dark -> Color.White.copy(alpha = 0.14f)
        else -> Color.Black.copy(alpha = 0.08f)
    }

    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(DialogFieldShape)
                .background(containerColor)
                .border(1.5.dp, borderColor, DialogFieldShape)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                val showClear = enabled && value.text.isNotEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = if (showClear) 0.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    if (showClear) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    onValueChange(TextFieldValue(""))
                                }
                                .semantics { contentDescription = "清空" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.11f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = cs.onSurface,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
        val message = errorText?.takeIf { it.isNotBlank() } ?: helperText?.takeIf { it.isNotBlank() }
        val counter = counterText?.takeIf { it.isNotBlank() }
        if (message != null || counter != null) {
            Spacer(Modifier.height(6.dp))
            val noteColor = if (isError) errorColor else cs.onSurfaceVariant
            if (isLargeFont() && message != null && counter != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = noteColor,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = noteColor,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = noteColor,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (counter != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            counter,
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = noteColor,
                        )
                    }
                }
            }
        }
    }
}
