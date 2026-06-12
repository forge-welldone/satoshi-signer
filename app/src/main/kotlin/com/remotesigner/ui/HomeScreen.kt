package com.remotesigner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.remotesigner.data.InboxItemEntity
import com.remotesigner.data.InboxStatus
import com.remotesigner.nostr.RelayStatus
import com.remotesigner.ui.branding.AppLogo
import com.remotesigner.ui.components.Addr
import com.remotesigner.ui.components.AppButton
import com.remotesigner.ui.components.AppButtonVariant
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.icons.AppIcons
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

private const val SIGNER_KEY_NPUB_HEAD = 5
private const val SIGNER_KEY_NPUB_TAIL = 5

@Composable
fun HomeScreen(
    npub: String,
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
    inboxItems: List<InboxItemEntity>,
    onPsbtSelected: (android.net.Uri) -> Unit,
    onSignInboxItem: (InboxItemEntity) -> Unit,
    onDeleteInboxItem: (InboxItemEntity) -> Unit,
    onItemTap: (InboxItemEntity) -> Unit = {},
    onContacts: () -> Unit = {},
    onEncryptPassphrase: () -> Unit = {},
    contactsCount: Int = 0,
    nfcAvailable: Boolean = false,
) {
    val colors = LocalVaultColors.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPsbtSelected) }

    val visibleInbox = remember(inboxItems) {
        inboxItems.filter { it.status != InboxStatus.DELETED }
    }
    val pendingCount = remember(visibleInbox) {
        visibleInbox.count {
            it.status == InboxStatus.PENDING ||
                it.status == InboxStatus.FAILED ||
                it.status == InboxStatus.SIGNING
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        BrandHeader(relayCount = relayCount, relayStatuses = relayStatuses)
        Spacer(modifier = Modifier.height(20.dp))

        HomeHero(
            visibleCount = visibleInbox.size,
            pendingCount = pendingCount,
        )
        Spacer(modifier = Modifier.height(18.dp))

        CtaGrid(
            onOpenPsbt = { launcher.launch(arrayOf("*/*")) },
            onContacts = onContacts,
            onEncryptPassphrase = onEncryptPassphrase,
            contactsCount = contactsCount,
            nfcAvailable = nfcAvailable,
        )
        Spacer(modifier = Modifier.height(24.dp))

        InboxHeaderRow()
        Spacer(modifier = Modifier.height(10.dp))
        InboxSection(
            items = visibleInbox,
            onSign = onSignInboxItem,
            onDelete = onDeleteInboxItem,
            onItemTap = onItemTap,
        )

        Spacer(modifier = Modifier.height(20.dp))
        SignerKeyExpandable(npub = npub)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun BrandHeader(relayCount: Int, relayStatuses: Map<String, RelayStatus>) {
    val typography = LocalVaultTypography.current
    val colors = LocalVaultColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLogo(size = 26.dp)
            Text(
                text = "Satoshi Signer",
                style = typography.title.copy(color = colors.text),
            )
        }
        RelayChip(state = relayChipState(relayCount, relayStatuses), relayCount = relayCount)
    }
}

private enum class RelayChipState { Connected, Connecting, Disconnected }

private fun relayChipState(
    relayCount: Int,
    relayStatuses: Map<String, RelayStatus>,
): RelayChipState = when {
    relayCount > 0 -> RelayChipState.Connected
    relayStatuses.values.any { it == RelayStatus.CONNECTING } -> RelayChipState.Connecting
    else -> RelayChipState.Disconnected
}

@Composable
private fun RelayChip(state: RelayChipState, relayCount: Int) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val (dotColor, label) = when (state) {
        RelayChipState.Connected -> colors.good to (
            if (relayCount == 1) "1 relay" else "$relayCount relays"
            )
        RelayChipState.Connecting -> colors.warn to "Connecting"
        RelayChipState.Disconnected -> colors.bad to "Offline"
    }
    Row(
        modifier = Modifier.testTag("relayChip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GlowingDot(color = dotColor)
        Text(
            text = label,
            style = typography.monoSmall.copy(color = colors.textDim),
        )
    }
}

@Composable
private fun GlowingDot(color: Color, size: Dp = 6.dp) {
    Canvas(modifier = Modifier.size(size * 2)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val coreRadius = size.toPx() / 2f
        drawCircle(color = color.copy(alpha = 0.18f), radius = coreRadius * 2.2f, center = center)
        drawCircle(color = color.copy(alpha = 0.35f), radius = coreRadius * 1.5f, center = center)
        drawCircle(color = color, radius = coreRadius, center = center)
    }
}

@Composable
private fun HomeHero(visibleCount: Int, pendingCount: Int) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current
    val (eyebrow, title, sub) = when {
        pendingCount > 0 -> {
            val plural = if (pendingCount == 1) "transaction" else "transactions"
            Triple(
                "Ready to sign",
                "$pendingCount $plural waiting for your signature.",
                "Review the outputs, plug in your Trezor, and sign offline.",
            )
        }
        visibleCount > 0 -> {
            val plural = if (visibleCount == 1) "transaction" else "transactions"
            Triple(
                "All caught up",
                "$visibleCount signed $plural in the inbox.",
                "Open one to broadcast or share, or dismiss it.",
            )
        }
        else -> Triple(
            "Standing by",
            "Waiting for a transaction to sign.",
            "Open a PSBT file, or send one from Electrum to this phone over Nostr.",
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .background(colors.surface)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Eyebrow(eyebrow)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = typography.display.copy(color = colors.text, fontSize = 24.sp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = sub,
            style = typography.bodyDim.copy(color = colors.textDim),
        )
    }
}

@Composable
private fun CtaGrid(
    onOpenPsbt: () -> Unit,
    onContacts: () -> Unit,
    onEncryptPassphrase: () -> Unit,
    contactsCount: Int,
    nfcAvailable: Boolean,
) {
    val colors = LocalVaultColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton(
            text = "Open PSBT file",
            onClick = onOpenPsbt,
            variant = AppButtonVariant.Primary,
            leadingIcon = { ButtonIcon(AppIcons.File, colors.accentInk) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                text = "Contacts · $contactsCount",
                onClick = onContacts,
                modifier = Modifier.weight(1f),
                variant = AppButtonVariant.Secondary,
                leadingIcon = { ButtonIcon(AppIcons.Contacts, colors.text) },
                small = true,
                fillMaxWidth = false,
            )
            if (nfcAvailable) {
                AppButton(
                    text = "NFC passphrase",
                    onClick = onEncryptPassphrase,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Secondary,
                    leadingIcon = { ButtonIcon(AppIcons.Nfc, colors.text) },
                    small = true,
                    fillMaxWidth = false,
                )
            }
        }
    }
}

@Composable
private fun ButtonIcon(image: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    val painter = rememberVectorPainter(image = image)
    Image(
        painter = painter,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun InboxHeaderRow() {
    val colors = LocalVaultColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Eyebrow("Inbox")
        Eyebrow("Via Nostr · NIP-04", color = colors.textDim)
    }
}

@Composable
private fun SignerKeyExpandable(npub: String) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val painter = rememberVectorPainter(image = AppIcons.Dot)
                Image(
                    painter = painter,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.accent),
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = "Your signer key",
                    style = typography.body.copy(color = colors.text),
                )
            }
            Addr(
                value = npub,
                head = SIGNER_KEY_NPUB_HEAD,
                tail = SIGNER_KEY_NPUB_TAIL,
                color = colors.textMute,
            )
        }
        if (expanded) {
            SignerKeyDetails(
                npub = npub,
                onCopy = { copyToClipboard(context, "npub", npub, "Copied npub") },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, npub)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, "Share signer key"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
            )
        }
    }
}

@Composable
private fun SignerKeyDetails(npub: String, onCopy: () -> Unit, onShare: () -> Unit) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        QrCodeImage(data = npub, size = 156.dp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = npub,
            style = typography.mono.copy(
                color = colors.textDim,
                fontSize = 11.sp,
            ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = "Copy",
                onClick = onCopy,
                variant = AppButtonVariant.Secondary,
                leadingIcon = { ButtonIcon(AppIcons.Copy, colors.text) },
                small = true,
                fillMaxWidth = false,
            )
            AppButton(
                text = "Share",
                onClick = onShare,
                variant = AppButtonVariant.Ghost,
                small = true,
                fillMaxWidth = false,
            )
        }
    }
}

@Composable
private fun QrCodeImage(data: String, size: Dp) {
    val colors = LocalVaultColors.current
    val bitmap = remember(data) {
        val pixels = 384
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, pixels, pixels)
        val bmp = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.RGB_565)
        for (x in 0 until pixels) {
            for (y in 0 until pixels) {
                bmp.setPixel(
                    x,
                    y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                )
            }
        }
        bmp
    }
    Box(
        modifier = Modifier
            .background(Color.White, LocalVaultShapes.current.small)
            .padding(8.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Nostr public key QR code",
            modifier = Modifier.size(size),
        )
    }
}
