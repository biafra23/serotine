package eth.serotine.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons :ui needs that material-icons-core lacks, as [ImageVector]s built from the path
 * data of Google's Material Symbols (Outlined, weight 400, 24 px; Apache License 2.0,
 * https://github.com/google/material-design-icons). A handful of paths instead of the
 * extended icon set, which would add megabytes to an APK that R8 does not shrink.
 * Each is tinted by the [androidx.compose.material3.Icon] that draws it.
 */
object SerotineIcons {
    /** A QR code. Material Symbols `qr_code`. */
    val QrCode: ImageVector by lazy {
        symbol(
            "QrCode",
            "M120-520v-320h320v320H120Zm80-80h160v-160H200v160Zm-80 480v-320h320v320H120Zm80-80" +
            "h160v-160H200v160Zm320-320v-320h320v320H520Zm80-80h160v-160H600v160Zm160 480v-80h80" +
            "v80h-80ZM520-360v-80h80v80h-80Zm80 80v-80h80v80h-80Zm-80 80v-80h80v80h-80Zm80 80v-80" +
            "h80v80h-80Zm80-80v-80h80v80h-80Zm0-160v-80h80v80h-80Zm80 80v-80h80v80h-80Z",
        )
    }

    /** Two overlapping sheets: copy. Material Symbols `content_copy`. */
    val ContentCopy: ImageVector by lazy {
        symbol(
            "ContentCopy",
            "M360-240q-33 0-56.5-23.5T280-320v-480q0-33 23.5-56.5T360-880h360q33 0 56.5 23.5" +
            "T800-800v480q0 33-23.5 56.5T720-240H360Zm0-80h360v-480H360v480ZM200-80" +
            "q-33 0-56.5-23.5T120-160v-560h80v560h440v80H200Zm160-240v-480 480Z",
        )
    }

    /** A wallet: the Accounts tab. Material Symbols `account_balance_wallet`. */
    val Wallet: ImageVector by lazy {
        symbol(
            "Wallet",
            "M200-200v-560 560Zm0 80q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560" +
            "q33 0 56.5 23.5T840-760v100h-80v-100H200v560h560v-100h80v100q0 33-23.5 56.5T760-120" +
            "H200Zm320-160q-33 0-56.5-23.5T440-360v-240q0-33 23.5-56.5T520-680h280q33 0 56.5 23.5" +
            "T880-600v240q0 33-23.5 56.5T800-280H520Zm280-80v-240H520v240h280Zm-160-60" +
            "q25 0 42.5-17.5T700-480q0-25-17.5-42.5T640-540q-25 0-42.5 17.5T580-480" +
            "q0 25 17.5 42.5T640-420Z",
        )
    }

    /** An eye: watching an address. Material Symbols `visibility`. */
    val Visibility: ImageVector by lazy {
        symbol(
            "Visibility",
            "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500" +
            "q0 75 52.5 127.5T480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608" +
            "q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-146 0-266-81.5T40-500" +
            "q54-137 174-218.5T480-800q146 0 266 81.5T920-500q-54 137-174 218.5T480-200Zm0-300Z" +
            "m0 220q113 0 207.5-59.5T832-500q-50-101-144.5-160.5T480-720q-113 0-207.5 59.5" +
            "T128-500q50 101 144.5 160.5T480-280Z",
        )
    }

    /** A key: full control of an account. Material Symbols `key`. */
    val Key: ImageVector by lazy {
        symbol(
            "Key",
            "M280-400q-33 0-56.5-23.5T200-480q0-33 23.5-56.5T280-560q33 0 56.5 23.5T360-480q0 33-23.5 56.5" +
            "T280-400Zm0 160q-100 0-170-70T40-480q0-100 70-170t170-70q67 0 121.5 33t86.5 87h352l120 120" +
            "-180 180-80-60-80 60-85-60h-47q-32 54-86.5 87T280-240Zm0-80q56 0 98.5-34t56.5-86h125l58 41" +
            " 82-61 71 55 75-75-40-40H435q-14-52-56.5-86T280-640q-66 0-113 47t-47 113q0 66 47 113t113 47Z",
        )
    }

    /** A person with a key: one of a Safe's signers. Material Symbols `passkey`. */
    val Passkey: ImageVector by lazy {
        symbol(
            "Passkey",
            "M120-160v-112q0-34 17.5-62.5T184-378q62-31 126-46.5T440-440q20 0 40 1.5t40 4.5q-4 58 21 109.5" +
            "t73 84.5v80H120ZM760-40l-60-60v-186q-44-13-72-49.5T600-420q0-58 41-99t99-41q58 0 99 41t41 99" +
            "q0 45-25.5 80T790-290l50 50-60 60 60 60-80 80ZM440-480q-66 0-113-47t-47-113q0-66 47-113" +
            "t113-47q66 0 113 47t47 113q0 66-47 113t-113 47Zm300 80q17 0 28.5-11.5T780-440q0-17-11.5-28.5" +
            "T740-480q-17 0-28.5 11.5T700-440q0 17 11.5 28.5T740-400Z",
        )
    }

    /** Two arrows, one each way: a swap. Material Symbols `swap_horiz`. */
    val Swap: ImageVector by lazy {
        symbol(
            "Swap",
            "M280-160 80-360l200-200 56 57-103 103h287v80H233l103 103-56 57Zm400-240-56-57 103-103H440v-80" +
            "h287L624-743l56-57 200 200-200 200Z",
        )
    }

    /** Three lines, shortest last: the order of a list. Material Symbols `sort`. */
    val Sort: ImageVector by lazy {
        symbol("Sort", "M120-240v-80h240v80H120Zm0-200v-80h480v80H120Zm0-200v-80h720v80H120Z")
    }

    /** An arrow into a tray: importing a recovery phrase. Material Symbols `download`. */
    val Download: ImageVector by lazy {
        symbol(
            "Download",
            "M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160" +
            "q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z",
        )
    }

    /** A hardware chip: P-256 hardware-bound key. Material Symbols `secure`. */
    val HardwareP256: ImageVector by lazy {
        symbol(
            "HardwareP256",
            "M360-80q-75 0-127.5-52.5T160-240v-480q0-75 52.5-127.5T320-920h320q75 0 127.5 52.5T800-760v480q0 75-52.5 127.5T640-120H360Zm0-80h280v-480H360v480Zm340-120" +
            "q25 0 42.5-17.5T740-440q0-25-17.5-42.5T680-480q-25 0-42.5 17.5T620-440q0 25 17.5 42.5T680-380Zm-200" +
            "0q25 0 42.5-17.5T540-440q0-25-17.5-42.5T480-480q-25 0-42.5 17.5T420-440q0 25 17.5 42.5T480-380Zm200" +
            "-160q25 0 42.5-17.5T740-600q0-25-17.5-42.5T680-640q-25 0-42.5 17.5T620-600q0 25 17.5 42.5T680-540Zm-200" +
            "0q25 0 42.5-17.5T540-600q0-25-17.5-42.5T480-640q-25 0-42.5 17.5T420-600q0 25 17.5 42.5T480-540Zm-160" +
            "80v-80h80v80H560Zm160-160v-80h80v80H720Zm-160 80v-80h80v80H560ZM360-400v-80h80v80H360Zm0-160v-80h80v80H360Z",
        )
    }
}

/** A Material Symbol: its path is drawn in a 960-unit square whose y axis runs from -960 to 0. */
private fun symbol(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 960f, viewportHeight = 960f)
        .addGroup(translationY = 960f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
