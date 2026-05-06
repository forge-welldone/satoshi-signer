package com.remotesigner.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 16dp ImageVector ports of the Vault prototype icon set. All paths stroke
 * `currentColor` (consumers tint via `colorFilter` or `tint`).
 *
 * Reference: handoff `src/ui.jsx` `Icon.*` factories.
 */
object AppIcons {
    private val Tint = SolidColor(Color.Black)

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroked(
        d: String,
        width: Float = 1.4f,
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
    ) {
        addPath(
            pathData = addPathNodes(d),
            stroke = Tint,
            strokeLineWidth = width,
            strokeLineCap = cap,
            strokeLineJoin = join,
        )
    }

    private fun ImageVector.Builder.filled(d: String) {
        addPath(
            pathData = addPathNodes(d),
            fill = Tint,
        )
    }

    val File = icon("file") {
        stroked("M4 2h5l3 3v9H4z")
        stroked("M9 2v3h3")
    }

    val Contacts = icon("contacts") {
        stroked("M5.5 6 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0")
        stroked("M3 14c0.5 -2.5 2.5 -4 5 -4s4.5 1.5 5 4")
    }

    val Nfc = icon("nfc") {
        filled("M5.2 12 a1.2 1.2 0 1 0 -2.4 0 a1.2 1.2 0 1 0 2.4 0")
        stroked("M4 8.5 A 3.5 3.5 0 0 1 7.5 12")
        stroked("M4 5.5 A 6.5 6.5 0 0 1 10.5 12")
        stroked("M4 2.5 A 9.5 9.5 0 0 1 13.5 12")
    }

    val Copy = icon("copy") {
        stroked("M5 5 h8 v8 h-8 z")
        stroked("M3 11V4a1 1 0 0 1 1 -1h7")
    }

    val Check = icon("check") {
        stroked("M3 8 l3 3 l7 -7", width = 2f)
    }

    val Warn = icon("warn") {
        stroked("M8 2 L14 13 H2 Z", width = 1.5f)
        stroked("M8 6 v3", width = 1.5f)
        filled("M8.6 11.3 a0.6 0.6 0 1 0 -1.2 0 a0.6 0.6 0 1 0 1.2 0")
    }

    val ArrowIn = icon("arrowIn") {
        stroked("M3 8 h9", width = 1.5f)
        stroked("M8 4 l4 4 -4 4", width = 1.5f)
    }

    val ArrowOut = icon("arrowOut") {
        stroked("M13 8 H4", width = 1.5f)
        stroked("M8 4 l-4 4 4 4", width = 1.5f)
    }

    val Usb = icon("usb") {
        stroked(
            "M4.5 9 h7 a1 1 0 0 0 1 -1 v-1.5 a1.5 1.5 0 0 0 -1.5 -1.5 h-6 a1.5 1.5 0 0 0 -1.5 1.5 v1.5 a1 1 0 0 0 1 1 z",
            width = 1.3f,
        )
        stroked("M6.5 9 v2.5 a1 1 0 0 0 1 1 h1 a1 1 0 0 0 1 -1 v-2.5", width = 1.3f)
        filled("M5.7 6.8 h1.1 v0.8 h-1.1 z")
        filled("M9.2 6.8 h1.1 v0.8 h-1.1 z")
        stroked("M7.5 5 V 3.5 a0.5 0.5 0 0 1 1 0 V 5", width = 1.3f)
    }

    val Trezor = icon("trezor") {
        stroked(
            "M4 1.5 h8 a1.2 1.2 0 0 1 1.2 1.2 v10.6 a1.2 1.2 0 0 1 -1.2 1.2 h-8 a1.2 1.2 0 0 1 -1.2 -1.2 v-10.6 a1.2 1.2 0 0 1 1.2 -1.2 z",
            width = 1.3f,
        )
        filled("M5.3 3 h5.4 v3.2 h-5.4 z")
        stroked("M7.8 9.3 a1.1 1.1 0 1 0 -2.2 0 a1.1 1.1 0 1 0 2.2 0", width = 1.3f)
        stroked("M10.4 9.3 a1.1 1.1 0 1 0 -2.2 0 a1.1 1.1 0 1 0 2.2 0", width = 1.3f)
        filled("M6.8 13.2 h2.4 v0.8 h-2.4 z")
    }

    val TrezorShield = icon("trezorShield") {
        stroked("M8 2 L13 4 v5 c0 2.5 -2 4.5 -5 5 -3 -0.5 -5 -2.5 -5 -5 V 4 z")
        stroked("M6 8 l1.5 1.5 L10.5 6.5", width = 1.4f)
    }

    val TrezorChip = icon("trezorChip") {
        stroked("M4 4 h8 v8 h-8 z", width = 1.3f)
        stroked("M6 6 h4 v4 h-4 z", width = 1.3f)
        stroked(
            "M2 6h2 M2 8h2 M2 10h2 M12 6h2 M12 8h2 M12 10h2 M6 2v2 M8 2v2 M10 2v2 M6 12v2 M8 12v2 M10 12v2",
            width = 1.3f,
        )
    }

    val TrezorKey = icon("trezorKey") {
        stroked("M7.5 10 a2.5 2.5 0 1 0 -5 0 a2.5 2.5 0 1 0 5 0", width = 1.4f)
        stroked("M7 9 L13 3", width = 1.4f)
        stroked("M10 6 L12 8", width = 1.4f)
        stroked("M11.5 4.5 L13.5 6.5", width = 1.4f)
    }

    val TrezorLock = icon("trezorLock") {
        stroked("M3.5 7 h9 v7 h-9 z", width = 1.4f)
        stroked("M5.5 7 v-2 a2.5 2.5 0 0 1 5 0 v2", width = 1.4f)
        filled("M8.9 10.5 a0.9 0.9 0 1 0 -1.8 0 a0.9 0.9 0 1 0 1.8 0")
    }

    val Broadcast = icon("broadcast") {
        filled("M9.2 8 a1.2 1.2 0 1 0 -2.4 0 a1.2 1.2 0 1 0 2.4 0")
        stroked("M5 5 Q3.5 8 5 11", width = 1.4f, join = StrokeJoin.Miter)
        stroked("M11 5 Q12.5 8 11 11", width = 1.4f, join = StrokeJoin.Miter)
        stroked("M3 3 Q0.5 8 3 13", width = 1.4f, join = StrokeJoin.Miter)
        stroked("M13 3 Q15.5 8 13 13", width = 1.4f, join = StrokeJoin.Miter)
    }

    val Keyboard = icon("keyboard") {
        stroked("M1.5 4 h13 v8 h-13 z", width = 1.3f)
        stroked(
            "M4 7 h0.01 M6 7 h0.01 M8 7 h0.01 M10 7 h0.01 M12 7 h0.01 M4.5 9.8 h7",
            width = 1.3f,
        )
    }

    val Plus = icon("plus") {
        stroked("M8 3 v10", width = 1.6f)
        stroked("M3 8 h10", width = 1.6f)
    }

    val ChevronLeft = icon("chevronLeft") {
        stroked("M10 3 L5 8 L10 13", width = 1.5f)
    }

    val Dot = icon("dot") {
        filled("M9 8 a1 1 0 1 0 -2 0 a1 1 0 1 0 2 0")
    }
}
