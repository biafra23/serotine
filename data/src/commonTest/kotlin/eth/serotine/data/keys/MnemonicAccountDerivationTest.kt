package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.repository.Derivation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MnemonicAccountDerivationTest {

    private val derivation = MnemonicAccountDerivation()

    @Test
    fun derivesTheFirstAccountOtherWalletsShowForTheSameWords() = runTest {
        // m/44'/60'/0'/0/0 of two widely published development mnemonics: the all-"abandon"
        // one, and Hardhat's and Anvil's default "test … junk" (their account #0).
        assertEquals(
            Derivation.Derived(Address.of("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")),
            derivation.deriveFirstAccount(Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")),
        )
        assertEquals(
            Derivation.Derived(Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")),
            derivation.deriveFirstAccount(Mnemonic("test test test test test test test test test test test junk")),
        )
    }

    @Test
    fun caseAndSpacingDoNotChangeTheAccount() = runTest {
        val typed = Mnemonic("  Test test TEST test\ntest test  test test test test test Junk ")

        assertEquals(
            Derivation.Derived(Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")),
            derivation.deriveFirstAccount(typed),
        )
    }

    @Test
    fun wordsThatAreNotAMnemonicAreRefusedWithTheReason() = runTest {
        assertEquals(
            Derivation.Invalid(MnemonicProblem.WordCount(11)),
            derivation.deriveFirstAccount(Mnemonic("test test test test test test test test test test junk")),
        )
        assertEquals(
            Derivation.Invalid(MnemonicProblem.Checksum),
            derivation.deriveFirstAccount(Mnemonic("test test test test test test test test test test test test")),
        )
        // A French BIP-39 word: another language's list is refused, not guessed at.
        assertEquals(
            Derivation.Invalid(MnemonicProblem.UnknownWord(position = 1)),
            derivation.deriveFirstAccount(Mnemonic("abaisser test test test test test test test test test test junk")),
        )
    }
}
