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

    /** Two arrows, one each way: a swap. Material Symbols `swap_horiz`. */
    val Swap: ImageVector by lazy {
        symbol(
            "Swap",
            "M280-160 80-360l200-200 56 57-103 103h287v80H233l103 103-56 57Zm400-240-56-57 103-103H440v-80" +
            "h287L624-743l56-57 200 200-200 200Z",
        )
    }

    /** An arrow into a tray: importing a recovery phrase. Material Symbols `download`. */
    val Download: ImageVector by lazy {
        symbol(
            "Download",
            "M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160" +
            "q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z",
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
