package tech.musxi.player.ui.me

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import tech.musxi.player.ui.components.RowCheck
import tech.musxi.player.ui.components.RowChevron
import tech.musxi.player.ui.components.RowSwitch
import tech.musxi.player.ui.components.sectionCardColor

/*
 * The controls of a row now live in `ui/components/RowControls.kt`, shared with every other page.
 * These names stay because the account page's AboutSection draws its auto-check switch with
 * `MeSwitch`, and the page's callers import them.
 */

/** The card colour of a grouped list. */
@Composable
fun meCardColor(): Color = sectionCardColor()

/** The page's one switch; it only shows state, the row around it is the tap target. */
@Composable
fun MeSwitch(checked: Boolean) = RowSwitch(checked)

/** Right-pointing chevron at the end of a row that opens something. */
@Composable
fun MeChevron() = RowChevron()

/** Selected mark of a single-choice row. */
@Composable
fun MeCheck() = RowCheck()
