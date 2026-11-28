package tech.musxi.player.ui.localaudio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import tech.musxi.player.ui.components.MusxiConfirmDialog
import tech.musxi.player.ui.components.MusxiDialogTextField
import tech.musxi.player.ui.components.MusxiTextFieldDialog

/**
 * Asks for one name: a folder's or a recording's. The rule is checked as the user types, so the
 * save button is off for a name the backend would refuse, and both buttons are off while saving so
 * a second tap cannot send the command twice.
 *
 * [onRestore] adds "恢复原名" for a renamed recording: it clears the user's name and the tag title or
 * file name shows again. Pass [forceSolid] = true when shown above a glass [MusxiSheet].
 */
@Composable
internal fun LocalNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRestore: (() -> Unit)? = null,
    forceSolid: Boolean = false,
    visible: Boolean = true,
) {
    // The whole name is selected, so typing replaces it and a small edit is one tap away.
    var value by remember(title, initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val seenOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            if (seenOpen[0]) value = TextFieldValue(initial, TextRange(0, initial.length))
            seenOpen[0] = true
        }
    }
    val problem = nameProblem(value.text)
    MusxiTextFieldDialog(
        visible = visible,
        title = title,
        value = value,
        onValueChange = { value = it },
        confirmLabel = confirmLabel,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value.text.trim()) },
        confirmEnabled = problem == null,
        placeholder = "输入名称",
        errorText = problem?.takeIf { value.text.isNotEmpty() },
        busy = saving,
        busyLabel = "正在保存…",
        forceSolid = forceSolid,
        eyebrow = "本地音频",
        auxiliaryLabel = if (onRestore != null) "恢复原名" else null,
        onAuxiliary = onRestore,
    )
}

/**
 * The name field on its own, kept for direct unit testing of the naming rules and capsule field.
 */
@Composable
internal fun LocalNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    problem: String?,
    saving: Boolean,
) {
    MusxiDialogTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = !saving,
        placeholder = "输入名称",
        // An empty field is the starting state, not a mistake; only say why once typed.
        errorText = problem?.takeIf { value.text.isNotEmpty() },
    )
}

/** A yes/no before something the user cannot undo from here. [danger] paints the confirm button red. */
@Composable
internal fun LocalConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    note: String? = null,
    visible: Boolean = true,
) {
    MusxiConfirmDialog(
        visible = visible,
        title = title,
        body = text,
        note = note,
        confirmLabel = confirmLabel,
        danger = danger,
        busy = saving,
        busyLabel = "正在处理…",
        eyebrow = "本地音频",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
