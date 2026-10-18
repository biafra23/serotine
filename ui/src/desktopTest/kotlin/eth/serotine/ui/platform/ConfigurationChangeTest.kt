package eth.serotine.ui.platform

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [OnStopUnlessConfigurationChange], what the seed screens hide their words on (owner ruling,
 * 2026-10-18, #39): a stop for a configuration change keeps them, any other stop does not.
 */
class ConfigurationChangeTest {

    @get:Rule
    val rule = createComposeRule()

    private val host = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private var changing = false
    private var hidden = 0

    private fun show() = rule.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides host) {
            OnStopUnlessConfigurationChange(isChangingConfigurations = { changing }) { hidden++ }
        }
    }

    private fun stopAndStart() = rule.runOnIdle {
        host.lifecycle.currentState = Lifecycle.State.CREATED
        host.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @Test
    fun aStopForAConfigurationChangeKeepsTheWords() {
        show()
        changing = true

        stopAndStart()

        rule.runOnIdle { assertEquals(0, hidden, "a rotation: the words stay in the ViewModel's memory") }
    }

    @Test
    fun anyOtherStopClearsThem() {
        show()

        stopAndStart()
        rule.runOnIdle { assertEquals(1, hidden, "the background, the screen off: the words go") }

        changing = true
        stopAndStart()
        rule.runOnIdle { assertEquals(1, hidden) }
        changing = false
        stopAndStart()
        rule.runOnIdle { assertEquals(2, hidden) }
    }
}
