package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.usecase.SafeControl

/** What the user can do with an account (docs/wallet-ux.md, *Control is a badge*). */
enum class Control(val label: String) {
    /** Its key is held here (or, for a Safe, enough of its owners' keys to reach its threshold). */
    FULL_CONTROL("Full control"),

    /** A Safe with some of its owners held here, fewer than its threshold. */
    CO_SIGNER("Co-signer"),

    /** Nothing is held here: balances and Receive, never Send. */
    WATCH_ONLY("Watch-only"),
    ;

    /** What the badge shows before [label], and alone where the label does not fit on one line. */
    val icon: ImageVector
        get() = when (this) {
            FULL_CONTROL -> SerotineIcons.Key
            CO_SIGNER -> SerotineIcons.Passkey
            WATCH_ONLY -> SerotineIcons.Visibility
        }

    companion object {
        /**
         * The control an account of [kind] held as [custody] gives. A Safe's depends on its
         * owners and threshold ([of] its [SafeControl]): until they are read it is shown as
         * watch-only, never as more than is known.
         */
        fun of(kind: AccountKind, custody: AccountCustody): Control = when {
            kind == AccountKind.SAFE -> WATCH_ONLY
            custody == AccountCustody.InKeyStore -> FULL_CONTROL
            else -> WATCH_ONLY
        }

        /** A Safe's control, as its verified owners and the keys held here make it. */
        fun of(control: SafeControl): Control = when (control) {
            SafeControl.FULL -> FULL_CONTROL
            SafeControl.CO_SIGNER -> CO_SIGNER
            SafeControl.WATCH_ONLY -> WATCH_ONLY
        }
    }
}

/** [control] as a small label: its icon and its text, on one line. Beside a name, [NameWithControlBadge]. */
@Composable
fun ControlBadge(control: Control, modifier: Modifier = Modifier) {
    BadgeChip(control, modifier) { BadgeLabel(control) }
}

/** The tag of [NameWithControlBadge]'s badge in its text form, for tests. */
const val CONTROL_BADGE_TEXT_TAG = "control-badge-text"

/** The tag of [NameWithControlBadge]'s badge in its icon form, for tests. */
const val CONTROL_BADGE_ICON_TAG = "control-badge-icon"

/**
 * An account's [name] with its [control] badge after it, on one line (#68). The badge shows its
 * [Control.icon] and its text where name and badge fit side by side on one line, so the icon's
 * meaning is learned, else the icon alone, in the same colours and height: never its text broken
 * over two lines. The name keeps at least its longest word; where even the icon finds no room
 * beside that, the badge goes under the name.
 *
 * Either way a screen reader hears the badge's text after the name, and the account's own
 * screen shows the badge in full.
 */
@Composable
fun NameWithControlBadge(name: String, control: Control, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Text(name)
            // Both forms are composed and one is placed: neither speaks, the box after them does.
            BadgeChip(control, Modifier.clearAndSetSemantics { testTag = CONTROL_BADGE_TEXT_TAG }) {
                BadgeLabel(control, spoken = false)
            }
            BadgeChip(control, Modifier.clearAndSetSemantics { testTag = CONTROL_BADGE_ICON_TAG }) {
                Icon(control.icon, null, Modifier.padding(horizontal = IconPadding, vertical = 1.dp).size(iconSize()))
            }
            Box(Modifier.semantics { text = AnnotatedString(control.label) })
        },
        modifier = modifier,
    ) { (nameText, textChip, iconChip, spoken), constraints ->
        val width = constraints.maxWidth
        val gap = NameToBadge.roundToPx()
        val asText = textChip.measure(Constraints())
        // The name's width on one line, and its longest word: what it needs to stay readable.
        val oneLine = nameText.maxIntrinsicWidth(Constraints.Infinity)
        val longestWord = minOf(nameText.minIntrinsicWidth(Constraints.Infinity), oneLine)
        val badge = if (oneLine + gap + asText.width <= width) asText else iconChip.measure(Constraints())
        val beside = longestWord + gap + badge.width <= width
        val nameRoom = when {
            !constraints.hasBoundedWidth -> Constraints.Infinity
            beside -> width - gap - badge.width
            else -> width
        }
        val named = nameText.measure(Constraints(maxWidth = nameRoom))
        val voice = spoken.measure(Constraints.fixed(badge.width, badge.height))
        val contentWidth = if (beside) named.width + gap + badge.width else maxOf(named.width, badge.width)
        val contentHeight = if (beside) maxOf(named.height, badge.height) else named.height + badge.height
        val layoutWidth = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth, layoutHeight) {
            val badgeX = if (beside) named.width + gap else 0
            val badgeY = if (beside) Alignment.CenterVertically.align(badge.height, layoutHeight) else named.height
            // Relative: mirrored in a right-to-left layout, as the Row this replaced was.
            named.placeRelative(0, if (beside) Alignment.CenterVertically.align(named.height, layoutHeight) else 0)
            badge.placeRelative(badgeX, badgeY)
            voice.placeRelative(badgeX, badgeY)
        }
    }
}

/** The room between an account's name and its badge. */
private val NameToBadge = 8.dp

/** The badge's padding after its text. */
private val TextPadding = 6.dp

/** The badge's padding beside its icon, and between its icon and its text. */
private val IconPadding = 4.dp

/** The badge's shape, in [control]'s colours, around [content]. */
@Composable
private fun BadgeChip(control: Control, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (background: Color, foreground: Color) = when (control) {
        Control.FULL_CONTROL -> colors.secondaryContainer to colors.onSecondaryContainer
        Control.CO_SIGNER -> colors.tertiaryContainer to colors.onTertiaryContainer
        Control.WATCH_ONLY -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = background, contentColor = foreground) {
        content()
    }
}

/**
 * [control]'s icon and text, as the badge shows them where they fit. Not [spoken] where another
 * node says the text: the text form of [NameWithControlBadge], which may be composed but not placed.
 */
@Composable
private fun BadgeLabel(control: Control, spoken: Boolean = true) {
    Row(
        Modifier.padding(start = IconPadding, end = TextPadding, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(IconPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(control.icon, null, Modifier.size(iconSize()))
        Text(
            control.label,
            if (spoken) Modifier else Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelSmall,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/** The badge's icon, as tall as a line of its text: the badge keeps its height in either form. */
@Composable
private fun iconSize(): Dp {
    val lineHeight = MaterialTheme.typography.labelSmall.lineHeight
    // Only an sp line height converts to dp; an em or unspecified one keeps labelSmall's 16.
    return if (lineHeight.isSp) with(LocalDensity.current) { lineHeight.toDp() } else 16.dp
}
