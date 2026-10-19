package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The router a swap goes through, or an approval lets take the tokens: [name] and its EIP-55 [address]. */
data class SpenderItem(val name: String, val address: String)

/**
 * One pool of a swap's route: [pair] ("USDC → WETH"), its [fee] tier ("0.05 %") and the EIP-55
 * [pool] the pinned factory returned.
 */
data class RouteStep(val pair: String, val fee: String, val pool: String)

/** A swap's route, one pool per row: the pair, its fee tier and the pool's address. */
@Composable
internal fun RouteSteps(route: List<RouteStep>) {
    route.forEach { step ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Blockie(step.pool, size = 24.dp)
            Column {
                Text("${step.pair} · ${step.fee}", style = MaterialTheme.typography.bodyMedium)
                Text(step.pool, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
internal fun AmountLine(icon: String?, symbol: String, amount: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(icon, symbol, size = 40.dp)
        SelectionContainer { Text(amount, style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
internal fun Spender(spender: SpenderItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(spender.address, size = 32.dp)
        Column {
            Text(spender.name, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(spender.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
