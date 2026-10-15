package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem

/**
 * The port that turns BIP-39 words into the account they control. :data implements it in
 * the key adapter (`eth.serotine.data.keys`), the one place plaintext key material may
 * exist (CLAUDE.md, *Trust*); what comes back up is an address, never a key.
 */
interface AccountDerivation {

    /**
     * The address of the FIRST account of [mnemonic]: the BIP-39 seed of its words with
     * the empty passphrase, derived along BIP-44's Ethereum path `m/44'/60'/0'/0/0` — the
     * account MetaMask, Ledger Live and Trezor list first for the same words. Coin type 60
     * on every network, Sepolia included, as those wallets do (not SLIP-44's testnet coin
     * type 1): an address is the same account on every chain.
     *
     * The words are read against the BIP-39 English wordlist; words of another language
     * are refused as [MnemonicProblem.UnknownWord], not derived some other way. Derivation
     * only: the adapter keeps nothing — not the words, not the seed, not the key — so the
     * account it names cannot sign until a key store holds its key
     * (docs/keys-and-signing.md, *The envelope*).
     *
     * Never fails other than as [Derivation.Invalid]; runs off the caller's thread.
     */
    suspend fun deriveFirstAccount(mnemonic: Mnemonic): Derivation
}

/** The outcome of [AccountDerivation.deriveFirstAccount]. */
sealed interface Derivation {

    /** The words are a valid mnemonic, and [address] is its first account. */
    data class Derived(val address: Address) : Derivation

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives. */
    data class Invalid(val problem: MnemonicProblem) : Derivation
}
