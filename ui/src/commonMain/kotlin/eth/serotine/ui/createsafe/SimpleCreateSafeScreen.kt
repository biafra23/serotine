package eth.serotine.ui.createsafe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Create Safe in simple mode (docs/simple-ui.md): [CreateSafeScreen] for the same [state], with no
 * light-client strip and no block. An owner resolved from a name says it was checked on chain; the
 * review says the factory's run, checked on chain, returns the address, and keeps the blocks, the
 * fee's make-up, the payer's balance block and the run's block behind *Details*. The owners, the
 * threshold, the payer, the fee and every check are as in Detailed view.
 */
@Composable
fun SimpleCreateSafeScreen(
    state: CreateSafeUiState,
    actions: CreateSafeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = CreateSafeScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    modifier = modifier,
    details = (state as? CreateSafeUiState.Review)?.details().orEmpty(),
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun CreateSafeUiState.simple(): CreateSafeUiState = when (this) {
    is CreateSafeUiState.Owners -> copy(
        accounts = accounts.map { it.simple() },
        typed = typed.map { it.simple() },
        inputOwner = inputOwner?.simple(),
    )
    is CreateSafeUiState.Threshold -> copy(owners = owners.map { it.simple() })
    is CreateSafeUiState.Review -> copy(
        prediction = simplePrediction ?: prediction,
        owners = owners.map { it.simple() },
        quote = quote?.let {
            it.copy(
                feeDetail = "",
                balance = it.balance.copy(verifiedAt = ""),
                simulation = it.simpleSimulation,
            )
        },
    )
    else -> this
}

private fun OwnerChoice.simple() = copy(note = simpleNote ?: note)

private fun OwnerItem.simple() = copy(note = simpleNote ?: note)

/** What the review keeps behind *Details* in simple mode: what Detailed view says of the address and the quote. */
private fun CreateSafeUiState.Review.details(): List<String> = listOfNotNull(
    prediction.takeIf { simplePrediction != null },
    quote?.feeDetail,
    quote?.balance?.let { "${it.what}: ${it.before} → ${it.after}, ${it.verifiedAt}" },
    quote?.simulation,
)
