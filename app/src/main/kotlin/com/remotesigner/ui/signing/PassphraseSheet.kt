package com.remotesigner.ui.signing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotesigner.ui.components.AppButton
import com.remotesigner.ui.components.AppButtonVariant
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.icons.AppIcons
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

enum class PassphraseMode { Trezor, Phone, Nfc }

@Composable
fun PassphraseSheetContent(
    mode: PassphraseMode?,
    onModeChange: (PassphraseMode) -> Unit,
    phonePassphrase: String,
    onPhonePassphraseChange: (String) -> Unit,
    nfcAvailable: Boolean,
    nfcWaiting: Boolean,
    nfcError: String?,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current

    Column(modifier = modifier.fillMaxWidth()) {
        SheetHandle()
        Eyebrow(text = "Passphrase required")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Where do you want to enter it?",
            style = typography.title.copy(
                color = colors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard(
                selected = mode == PassphraseMode.Trezor,
                icon = AppIcons.Trezor,
                label = "Type on Trezor",
                sub = "On-device screen (safest).",
                onClick = { onModeChange(PassphraseMode.Trezor) },
            )
            ModeCard(
                selected = mode == PassphraseMode.Phone,
                icon = AppIcons.Keyboard,
                label = "Type on this phone",
                sub = "Keyboard learning disabled.",
                onClick = { onModeChange(PassphraseMode.Phone) },
            )
            if (nfcAvailable) {
                ModeCard(
                    selected = mode == PassphraseMode.Nfc,
                    icon = AppIcons.Nfc,
                    label = "Tap an NFC tag",
                    sub = "Encrypted NIP-04 payload.",
                    onClick = { onModeChange(PassphraseMode.Nfc) },
                )
            }
        }

        if (mode == PassphraseMode.Phone) {
            Spacer(modifier = Modifier.height(12.dp))
            PassphraseInput(
                value = phonePassphrase,
                onChange = onPhonePassphraseChange,
            )
        }
        if (mode == PassphraseMode.Nfc) {
            Spacer(modifier = Modifier.height(12.dp))
            NfcAffordance(waiting = nfcWaiting, error = nfcError)
        }

        Spacer(modifier = Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = "Continue",
                onClick = onContinue,
                enabled = mode != null && (mode != PassphraseMode.Phone || phonePassphrase.isNotEmpty()),
                modifier = Modifier.testTag("passphraseContinue"),
            )
            AppButton(
                text = "Cancel",
                onClick = onCancel,
                variant = AppButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun SheetHandle() {
    val colors = LocalVaultColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(colors.lineStrong),
        )
    }
}

@Composable
private fun ModeCard(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    sub: String,
    onClick: () -> Unit,
) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val typography = LocalVaultTypography.current
    val interaction = remember { MutableInteractionSource() }
    val borderColor = if (selected) colors.accent else colors.line
    val background = if (selected) colors.surfaceElev else androidx.compose.ui.graphics.Color.Transparent
    val iconTint = if (selected) colors.accent else colors.textDim

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("passphraseMode-${label}")
            .clip(shapes.card)
            .border(1.dp, borderColor, shapes.card)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = rememberVectorPainter(image = icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconTint),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = typography.bodyDim.copy(color = colors.text))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = sub, style = typography.caption.copy(color = colors.textDim))
        }
        if (selected) {
            Image(
                painter = rememberVectorPainter(image = AppIcons.Check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.accent),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PassphraseInput(value: String, onChange: (String) -> Unit) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("passphrase", style = typography.mono.copy(color = colors.textMute)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrect = false,
        ),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = typography.mono.copy(color = colors.text),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(
                    text = if (visible) "Hide" else "Show",
                    style = typography.eyebrow.copy(color = colors.textDim),
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.lineStrong,
            unfocusedBorderColor = colors.lineStrong,
            focusedContainerColor = colors.surfaceSunken,
            unfocusedContainerColor = colors.surfaceSunken,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("passphraseField"),
    )
}

@Composable
private fun NfcAffordance(waiting: Boolean, error: String?) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val typography = LocalVaultTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .border(1.dp, colors.lineStrong, shapes.card)
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (waiting) "Hold the NFC tag to the back of the phone…" else "Tap Continue, then hold the NFC tag to the back of the phone.",
            style = typography.bodyDim.copy(color = colors.textDim),
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = typography.caption.copy(color = colors.bad),
            )
        }
    }
}
