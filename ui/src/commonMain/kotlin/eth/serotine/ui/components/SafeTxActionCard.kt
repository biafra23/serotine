package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Test tag of a Safe transaction's decoded title, for UI tests. */
const val SAFE_TX_ACTION_TAG = "safe-tx-action"

/**
 * A Safe transaction's [action] as decoded here (docs/wallet-ux.md, screen 17): its title, with
 * the asset's icon for a transfer, each address it names in full with its blockie, what else it
 * does, and its warning. What the user confirms before signing, so nothing is shortened.
 */
@Composable
fun SafeTxActionCard(action: SafeTxActionItem, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                action.symbol?.let { TokenIcon(action.icon, it, size = 32.dp) }
                Text(
                    action.title,
                    Modifier.testTag(SAFE_TX_ACTION_TAG),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            action.addresses.forEach { line ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Blockie(line.address, size = 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text(line.what, style = MaterialTheme.typography.labelMedium)
                        // In full: what the owners sign is this address.
                        SelectionContainer {
                            Text(
                                line.address,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            action.notes.forEach { note -> Text(note, style = MaterialTheme.typography.bodySmall) }
            action.warning?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Test tag of where a Safe's swap or its approval stands now, for UI tests. */
const val SAFE_SWAP_TAG = "safe-swap"

/** Test tag of the offer to propose a Safe's swap again, for UI tests. */
const val SAFE_SWAP_PROPOSE_AGAIN_TAG = "safe-swap-propose-again"

/**
 * Where a Safe's swap or its approval stands as the chain is now ([swap]): its lines, then what
 * to know before signing or executing it, in the error colour; and, where [onProposeAgain] is
 * given and the swap is worth it ([SafeSwapItem.proposeAgain]), the offer to propose it again,
 * greyed out when it cannot be done from here, a tap saying why.
 */
@Composable
fun SafeSwapBlock(swap: SafeSwapItem, modifier: Modifier = Modifier, onProposeAgain: (() -> Unit)? = null) {
    var unavailable by remember { mutableStateOf<String?>(null) }
    Column(modifier.testTag(SAFE_SWAP_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        swap.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        swap.warning?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (swap.proposeAgain && onProposeAgain != null) {
            val reason = swap.proposeAgainReason
            OutlinedButton(
                onClick = { if (reason == null) onProposeAgain() else unavailable = reason },
                modifier = Modifier.testTag(SAFE_SWAP_PROPOSE_AGAIN_TAG).unavailable(reason != null),
                colors = if (reason == null) {
                    ButtonDefaults.outlinedButtonColors()
                } else {
                    ButtonDefaults.outlinedButtonColors(contentColor = Unavailable.contentColor())
                },
            ) {
                Text("Propose the swap again")
            }
        }
    }
    unavailable?.let { reason ->
        UnavailableDialog(UnavailableNotice("Propose the swap again", reason), onDismiss = { unavailable = null })
    }
}
