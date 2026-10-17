package eth.serotine.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind

/** What the user can do with an account (docs/wallet-ux.md, *Control is a badge*). */
enum class Control(val label: String) {
    /** Its key is held here (or, for a Safe, enough of its owners' keys to reach its threshold). */
    FULL_CONTROL("Full control"),

    /** A Safe with some of its owners held here, fewer than its threshold. */
    CO_SIGNER("Co-signer"),

    /** Nothing is held here: balances and Receive, never Send. */
    WATCH_ONLY("Watch-only"),
    ;

    companion object {
        /**
         * The control an account of [kind] held as [custody] gives. A Safe's depends on its
         * owners and threshold, which nothing reads yet: until then it is shown as watch-only,
         * never as more than is known.
         */
        fun of(kind: AccountKind, custody: AccountCustody): Control = when {
            kind == AccountKind.SAFE -> WATCH_ONLY
            custody == AccountCustody.InKeyStore -> FULL_CONTROL
            else -> WATCH_ONLY
        }
    }
}

/** [control] as a small label beside an account's name. */
@Composable
fun ControlBadge(control: Control, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (background: Color, foreground: Color) = when (control) {
        Control.FULL_CONTROL -> colors.secondaryContainer to colors.onSecondaryContainer
        Control.CO_SIGNER -> colors.tertiaryContainer to colors.onTertiaryContainer
        Control.WATCH_ONLY -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = background, contentColor = foreground) {
        Text(
            control.label,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
