package tech.musxi.player.ui.localaudio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Asks for one name: a folder's or a recording's. The rule is checked as the user types, so the
 * save button is off for a name the backend would refuse, and both buttons are off while saving so
 * a second tap cannot send the command twice.
 *
 * [onRestore] adds "恢复原名" for a renamed recording: it clears the user's name and the tag title or
 * file name shows again.
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
) {
    // The whole name is selected, so typing replaces it and a small edit is one tap away.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val problem = nameProblem(value.text)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = { LocalNameField(value, { value = it }, problem, saving) },
        confirmButton = {
            Row {
                TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = !saving && problem == null) {
                    Text(if (saving) "正在保存…" else confirmLabel)
                }
            }
        },
        dismissButton = {
            Row {
                if (onRestore != null) {
                    TextButton(onClick = onRestore, enabled = !saving) { Text("恢复原名") }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            }
        },
    )
}

/**
 * The name field on its own, so it can be checked outside a dialog window: under Robolectric a text
 * field inside a Dialog window never lets Compose go idle, while the same field in the activity does.
 */
@Composable
internal fun LocalNameField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, problem: String?, saving: Boolean) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = !saving,
            // An empty field is the starting state, not a mistake; only say why once typed.
            isError = problem != null && value.text.isNotEmpty(),
            supportingText = if (problem != null && value.text.isNotEmpty()) ({ Text(problem) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A yes/no before something the user cannot undo from here. [danger] paints the yes red. */
@Composable
internal fun LocalConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                Text(confirmLabel, color = if (danger && !saving) cs.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
