package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account

/**
 * The name a new account gets when the user gives none: "Account N", N one more than the
 * number of accounts, raised past any such name already taken.
 */
internal fun nextFreeLabel(current: List<Account>): String {
    val taken = current.mapTo(HashSet()) { it.label }
    return generateSequence(current.size + 1) { it + 1 }.map { "Account $it" }.first { it !in taken }
}
