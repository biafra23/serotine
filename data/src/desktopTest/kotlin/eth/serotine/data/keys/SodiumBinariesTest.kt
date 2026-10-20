package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertNotNull

class SodiumBinariesTest {

    @Test
    fun theBindingsShipLibsodiumForEveryDesktopThisAppTargets() {
        // The jar carries every desktop's binary, so any machine checks them all: a wrong name
        // would make the key store unavailable on that whole OS, and only its users would see.
        val desktops = listOf(
            "mac os x" to "aarch64",
            "mac os x" to "x86_64",
            "windows 11" to "amd64",
            "linux" to "amd64",
            "linux" to "aarch64",
        )
        for ((os, arch) in desktops) {
            val resource = Sodium.desktopResource(os, arch)
            assertNotNull(Sodium::class.java.classLoader?.getResource(resource), "$resource for $os/$arch")
        }
    }
}
