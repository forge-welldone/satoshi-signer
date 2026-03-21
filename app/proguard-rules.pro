# ============================================================================
# Satoshi Signer — R8/ProGuard rules
# ============================================================================

# --- Chaquopy: classes accessed from Python via reflection ---
# Python calls methods on these objects through Chaquopy's proxy mechanism.
# R8 must not rename or remove them.

-keep class com.remotesigner.usb.SigningBridge { *; }
-keep class com.remotesigner.usb.UsbBridge { *; }
-keep class com.remotesigner.bridge.SigningCallback { *; }
-keep class com.remotesigner.bridge.SigningCallbackImpl { *; }

# --- secp256k1-kmp: JNI native library ---
# No consumer ProGuard rules shipped; keep all classes that may use JNI.

-keep class fr.acinq.secp256k1.** { *; }

# --- ZXing: no consumer ProGuard rules shipped ---
# Used for QR code generation (QRCodeWriter, BarcodeFormat).
# Keep only the QR code subset; R8 can strip unused barcode formats.

-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.common.** { *; }

# --- Google Tink (via security-crypto): compile-only annotations ---

-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
