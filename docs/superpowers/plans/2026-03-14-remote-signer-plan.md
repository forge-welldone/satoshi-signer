# Satoshi Signer Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that imports unsigned Bitcoin PSBTs, signs them with a Trezor Safe 3 via USB-C, and broadcasts the signed transaction.

**Architecture:** Kotlin/Jetpack Compose thin shell for UI + USB I/O, with Python backend (via Chaquopy) handling all Bitcoin/Trezor logic using trezorlib and embit. Custom trezorlib Transport subclass bridges Android USB to Python via Kotlin callbacks.

**Tech Stack:** Kotlin, Jetpack Compose, Chaquopy 17.0.0 (Python 3.13), trezorlib (trezor==0.13.9), embit, requests

**Spec:** `docs/superpowers/specs/2026-03-14-remote-signer-design.md`

---

## File Structure

```
remote_signer/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/remotesigner/
│       │   ├── MainActivity.kt
│       │   ├── ui/
│       │   │   ├── AppNavigation.kt
│       │   │   ├── HomeScreen.kt
│       │   │   ├── TransactionReviewScreen.kt
│       │   │   ├── SigningScreen.kt
│       │   │   ├── ResultScreen.kt
│       │   │   └── theme/Theme.kt
│       │   ├── viewmodel/SignerViewModel.kt
│       │   ├── usb/
│       │   │   ├── TrezorUsbManager.kt
│       │   │   └── UsbBridge.kt
│       │   └── bridge/PythonBridge.kt
│       ├── python/remotesigner/
│       │   ├── __init__.py
│       │   ├── psbt_parser.py
│       │   ├── usb_transport.py
│       │   ├── signer.py
│       │   ├── broadcaster.py
│       │   └── trezor_ui.py
│       └── res/
│           ├── values/strings.xml
│           └── values/themes.xml
├── build.gradle.kts          (project-level)
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── tests/                     (desktop Python tests)
│   ├── conftest.py
│   ├── test_psbt_parser.py
│   ├── test_broadcaster.py
│   ├── test_usb_transport.py
│   ├── test_signer.py
│   └── fixtures/
│       ├── single_sig_unsigned.psbt
│       └── multisig_partial.psbt
└── requirements-dev.txt       (desktop Python dev deps)
```

---

## Chunk 1: Project Setup and Dependency Validation

### Task 1: Create Android Project Scaffold

**Files:**
- Create: `build.gradle.kts` (project-level)
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/remotesigner/MainActivity.kt`
- Create: `app/src/main/python/remotesigner/__init__.py`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: Create project-level build.gradle.kts**

```kotlin
// build.gradle.kts (project root)
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}
```

- [ ] **Step 2: Create settings.gradle.kts**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RemoteSigner"
include(":app")
```

- [ ] **Step 3: Create gradle.properties**

```properties
# gradle.properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m
```

- [ ] **Step 4: Create gradle/libs.versions.toml**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
compose-bom = "2024.12.01"
activity-compose = "1.9.3"
navigation-compose = "2.8.5"
lifecycle = "2.8.7"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation-compose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 5: Create app/build.gradle.kts with Chaquopy**

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
}

android {
    namespace = "com.remotesigner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remotesigner"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("trezor==0.13.9")
            install("embit>=0.7")
            install("requests>=2.28")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation(libs.compose.ui.tooling)
}
```

- [ ] **Step 6: Create minimal AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name="com.chaquo.python.android.PyApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.RemoteSigner">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.RemoteSigner">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: Create minimal MainActivity.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/MainActivity.kt
package com.remotesigner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Satoshi Signer")
                }
            }
        }
    }
}
```

- [ ] **Step 8: Create Python package init and resource files**

`app/src/main/python/remotesigner/__init__.py`:
```python
"""Satoshi Signer - Bitcoin PSBT signing with Trezor on Android."""
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Satoshi Signer</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.RemoteSigner" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 9: Initialize Gradle wrapper and verify project builds**

Run:
```bash
cd /Users/sasha/Projects/remote_signer
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL (APK generated, Chaquopy downloads Python and pip packages)

- [ ] **Step 10: Commit scaffold**

```bash
git init
echo '.gradle/\nbuild/\napp/build/\n*.apk\nlocal.properties\n.idea/\n*.iml\n.venv/\n__pycache__/\n*.pyc' > .gitignore
git add -A
git commit -m "feat: initial Android project scaffold with Chaquopy"
```

---

### Task 2: Dependency Validation Under Chaquopy

**Files:**
- Create: `app/src/main/python/remotesigner/validate_deps.py`

This is the **blocking validation** before any feature work. If any dependency fails to import under Chaquopy, the architecture may need to change.

- [ ] **Step 1: Create validation script**

```python
# app/src/main/python/remotesigner/validate_deps.py
"""
Dependency validation for Chaquopy.
Run from Kotlin to verify all Python deps import correctly on Android.
Returns a dict with status for each dependency.
"""


def validate():
    """Test all dependency imports and basic functionality. Returns dict of results."""
    results = {}

    # 1. Test embit (PSBT parsing)
    try:
        from embit.psbt import PSBT
        from embit.transaction import Transaction
        from embit import script
        from embit.networks import NETWORKS
        # Also verify finalize_psbt location
        try:
            from embit.finalizer import finalize_psbt
            finalize_loc = "embit.finalizer"
        except ImportError:
            finalize_loc = "not found in embit.finalizer"
        results["embit"] = {"status": "ok", "detail": f"PSBT, Transaction, script imported. finalize: {finalize_loc}"}
    except Exception as e:
        results["embit"] = {"status": "error", "detail": str(e)}

    # 2. Test embit PSBT parsing with BIP-174 test vector
    try:
        import base64
        # BIP-174 test: a minimal valid PSBT (unsigned, 1 input, 1 output)
        # This is a creator role output from BIP-174 test vectors
        test_psbt_b64 = (
            "cHNidP8BAHUCAAAAASaBcTce3/KF6Tti/j+kvTVNP4Hc8TnREBEJCgAAAAAA"
            "/////wIA0kIAAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrI4GAAAA"
            "AAAAGXapFGBBiQn4uaVhYVMwwSxAkZfvFymsiKwAAAAAAAEA/QABAQAAAAAB"
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP////8EAlIAAP////8C"
            "gJaYAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrADh9QUAAAAAGXAP"
            "FIfu4mrLiIx25bW3hHiKRsp2h3dXiKwAAAAAIgYCCa0zHV2vKIiDQUzH/Y7r"
            "RihFHBApdtI/KHNbbsLGO7sY//////////8AAIABAACAAAAAAAAAAA=="
        )
        psbt = PSBT.parse(base64.b64decode(test_psbt_b64))
        assert len(psbt.inputs) >= 1
        results["embit_psbt"] = {"status": "ok", "detail": f"Parsed PSBT with {len(psbt.inputs)} inputs"}
    except Exception as e:
        results["embit_psbt"] = {"status": "error", "detail": str(e)}

    # 3. Test trezorlib core imports (bypass transport/libusb)
    try:
        # These are the modules we actually need - not the libusb transports
        from trezorlib import messages
        from trezorlib import btc
        results["trezorlib_core"] = {"status": "ok", "detail": "messages, btc imported"}
    except Exception as e:
        results["trezorlib_core"] = {"status": "error", "detail": str(e)}

    # 4. Test trezorlib Transport base class (for subclassing)
    try:
        from trezorlib.transport import Transport
        results["trezorlib_transport"] = {"status": "ok", "detail": "Transport base class imported"}
    except Exception as e:
        results["trezorlib_transport"] = {"status": "error", "detail": str(e)}

    # 5. Test trezorlib message construction
    try:
        from trezorlib import messages
        tx_input = messages.TxInputType(
            address_n=[0x80000000 | 84, 0x80000000 | 0, 0x80000000 | 0, 0, 0],
            prev_hash=bytes(32),
            prev_index=0,
            amount=100000,
            script_type=messages.InputScriptType.SPENDWITNESS,
        )
        assert tx_input.amount == 100000
        results["trezorlib_messages"] = {"status": "ok", "detail": "TxInputType constructed"}
    except Exception as e:
        results["trezorlib_messages"] = {"status": "error", "detail": str(e)}

    # 5b. Test PASSPHRASE_ON_DEVICE location
    try:
        try:
            from trezorlib.client import PASSPHRASE_ON_DEVICE
            pp_loc = "trezorlib.client"
        except ImportError:
            from trezorlib.tools import PASSPHRASE_ON_DEVICE
            pp_loc = "trezorlib.tools"
        results["passphrase_on_device"] = {"status": "ok", "detail": f"Found in {pp_loc}"}
    except Exception as e:
        results["trezorlib_messages"] = {"status": "error", "detail": str(e)}

    # 6. Test requests
    try:
        import requests
        results["requests"] = {"status": "ok", "detail": "requests imported"}
    except Exception as e:
        results["requests"] = {"status": "error", "detail": str(e)}

    # 7. Document trezorlib API version info
    try:
        from trezorlib.transport import Transport
        import inspect
        has_chunk_size = hasattr(Transport, 'CHUNK_SIZE')
        methods = [m for m in dir(Transport) if not m.startswith('_')]
        results["trezorlib_api"] = {
            "status": "info",
            "has_CHUNK_SIZE": has_chunk_size,
            "transport_methods": methods,
        }
    except Exception as e:
        results["trezorlib_api"] = {"status": "error", "detail": str(e)}

    # 8. Check if trezorlib imports trigger libusb loading
    try:
        from trezorlib.client import TrezorClient
        results["trezorlib_client"] = {"status": "ok", "detail": "TrezorClient imported"}
    except ImportError as e:
        if "libusb" in str(e) or "usb" in str(e).lower():
            results["trezorlib_client"] = {
                "status": "warning",
                "detail": f"libusb import issue (expected): {e}. Need to monkeypatch.",
            }
        else:
            results["trezorlib_client"] = {"status": "error", "detail": str(e)}
    except Exception as e:
        results["trezorlib_client"] = {"status": "error", "detail": str(e)}

    return results
```

- [ ] **Step 2: Add validation call in MainActivity**

Temporarily modify `MainActivity.kt` to run validation on startup:

```kotlin
// app/src/main/kotlin/com/remotesigner/MainActivity.kt
package com.remotesigner

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DependencyValidationScreen()
            }
        }
    }
}

@Composable
fun DependencyValidationScreen() {
    var results by remember { mutableStateOf("Running validation...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            results = withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("remotesigner.validate_deps")
                    val res = module.callAttr("validate")
                    res.toString()
                } catch (e: Exception) {
                    "FATAL: ${e.message}\n${e.stackTraceToString()}"
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Dependency Validation", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(results, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

- [ ] **Step 3: Build and run on Android device/emulator**

```bash
./gradlew installDebug
```

Expected: App launches and displays validation results. All dependencies show "ok" status.

**If trezorlib import fails due to libusb:** This is expected. The fix is to monkeypatch the transport imports before importing trezorlib.client. Create a transport patch module (addressed in Task 5).

- [ ] **Step 4: Document findings and commit**

Record which APIs are available, any monkeypatching needed, and the exact trezorlib Transport/Client API signatures. Update the spec if the trezorlib API differs from what was documented.

```bash
git add -A
git commit -m "feat: dependency validation under Chaquopy"
```

**GATE: Do not proceed to Chunk 2 until all dependencies import successfully under Chaquopy. If trezorlib fails to import, investigate and fix (monkeypatch transport imports, or pin a different version).**

---

## Chunk 2: Python PSBT Parser and Broadcaster

### Task 3: PSBT Parser Module

**Files:**
- Create: `app/src/main/python/remotesigner/psbt_parser.py`
- Create: `tests/test_psbt_parser.py`
- Create: `tests/conftest.py`
- Create: `tests/fixtures/` (test PSBT files)
- Create: `requirements-dev.txt`

The PSBT parser is pure Python, testable on desktop without Android.

- [ ] **Step 1: Create requirements-dev.txt for desktop testing**

```
# requirements-dev.txt
trezor==0.13.9
embit>=0.7
requests>=2.28
pytest>=7.0
pytest-mock>=3.10
```

```bash
cd /Users/sasha/Projects/remote_signer
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
```

- [ ] **Step 2: Create test fixtures**

Create a conftest.py that generates test PSBTs using embit:

```python
# tests/conftest.py
import sys
import os
import pytest

# Add Python source to path for desktop testing
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'python'))
```

- [ ] **Step 3: Write failing tests for psbt_parser**

```python
# tests/test_psbt_parser.py
import base64
import pytest
from remotesigner.psbt_parser import parse_psbt


# BIP-174 test vector: unsigned PSBT with 1 input, 2 outputs
# This is the "Creator" output from BIP-174
BIP174_CREATOR_B64 = (
    "cHNidP8BAHUCAAAAASaBcTce3/KF6Tti/j+kvTVNP4Hc8TnREBEJCgAAAAAA"
    "/////wIA0kIAAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrI4GAAAA"
    "AAAAGXapFGBBiQn4uaVhYVMwwSxAkZfvFymsiKwAAAAAAAEA/QABAQAAAAAB"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP////8EAlIAAP////8C"
    "gJaYAAAAAAAZdqkUdopAu9dAy+gdmI5x3ipNXHE5ax2IrADh9QUAAAAAGXAP"
    "FIfu4mrLiIx25bW3hHiKRsp2h3dXiKwAAAAAIgYCCa0zHV2vKIiDQUzH/Y7r"
    "RihFHBApdtI/KHNbbsLGO7sY//////////8AAIABAACAAAAAAAAAAA=="
)


class TestParsePsbt:
    def test_parses_valid_psbt_bytes(self):
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result, dict)
        assert len(result["inputs"]) >= 1
        assert len(result["outputs"]) >= 1

    def test_returns_fee(self):
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result["fee"], int)
        assert result["fee"] >= 0

    def test_outputs_have_address_and_amount(self):
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        for out in result["outputs"]:
            assert out["amount"] >= 0
            assert "address" in out

    def test_detects_change_output_by_derivation(self):
        """Change outputs have is_change=True when BIP32 derivation
        matches an input's master fingerprint with .../1/x path pattern."""
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        for out in result["outputs"]:
            assert "is_change" in out

    def test_rejects_invalid_bytes(self):
        with pytest.raises(ValueError, match="Invalid PSBT"):
            parse_psbt(b"not a psbt")

    def test_returns_signing_status(self):
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        assert result["status"] in ("unsigned", "partially_signed", "fully_signed")

    def test_returns_signer_fingerprints(self):
        psbt_bytes = base64.b64decode(BIP174_CREATOR_B64)
        result = parse_psbt(psbt_bytes)
        assert isinstance(result["signers"], list)
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
cd /Users/sasha/Projects/remote_signer
python -m pytest tests/test_psbt_parser.py -v
```
Expected: FAIL — `ImportError: cannot import name 'parse_psbt' from 'remotesigner.psbt_parser'`

- [ ] **Step 5: Implement psbt_parser.py**

```python
# app/src/main/python/remotesigner/psbt_parser.py
"""Parse PSBT files and extract transaction details for display."""

from dataclasses import dataclass, field
from embit.psbt import PSBT
from embit.networks import NETWORKS


PSBT_MAGIC = b"psbt\xff"


@dataclass
class ParsedTransaction:
    inputs: list = field(default_factory=list)
    outputs: list = field(default_factory=list)
    fee: int = 0
    status: str = "unsigned"  # "unsigned", "partially_signed", "fully_signed"
    signers: list = field(default_factory=list)  # [{fingerprint, signed}]
    raw_psbt: object = None  # The embit PSBT object for later signing


def parse_psbt(psbt_bytes: bytes, network: str = "main") -> ParsedTransaction:
    """Parse PSBT bytes and return structured transaction data.

    Args:
        psbt_bytes: Raw PSBT binary data.
        network: "main" or "test".

    Returns:
        ParsedTransaction with inputs, outputs, fee, signing status.

    Raises:
        ValueError: If the bytes are not a valid PSBT.
    """
    if not psbt_bytes.startswith(PSBT_MAGIC):
        raise ValueError("Invalid PSBT: missing magic bytes")

    try:
        psbt = PSBT.parse(psbt_bytes)
    except Exception as e:
        raise ValueError(f"Invalid PSBT: {e}") from e

    net = NETWORKS[network]
    tx = psbt.tx
    result = ParsedTransaction(raw_psbt=psbt)
    result.network = network

    # Collect all master fingerprints from inputs (to identify change outputs)
    input_fingerprints = set()
    for inp_scope in psbt.inputs:
        for pub, deriv in inp_scope.bip32_derivations.items():
            input_fingerprints.add(deriv.fingerprint)
        for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
            input_fingerprints.add(deriv.fingerprint)

    # Parse inputs
    for i, inp_scope in enumerate(psbt.inputs):
        utxo = psbt.utxo(i)
        inp_data = {
            "index": i,
            "txid": inp_scope.txid.hex() if hasattr(inp_scope, 'txid') else "",
            "vout": inp_scope.vout if hasattr(inp_scope, 'vout') else tx.vin[i].vout,
            "amount": utxo.value if utxo else 0,
        }
        result.inputs.append(inp_data)

    # Parse outputs and detect change
    for i, out_scope in enumerate(psbt.outputs):
        vout = tx.vout[i]
        address = vout.script_pubkey.address(net) if vout.script_pubkey else "unknown"

        is_change = _is_change_output(out_scope, input_fingerprints)

        out_data = {
            "index": i,
            "address": address,
            "amount": vout.value,
            "is_change": is_change,
        }
        result.outputs.append(out_data)

    # Fee
    try:
        result.fee = psbt.fee()
    except Exception:
        # If fee can't be computed (missing UTXO data), set to -1
        result.fee = -1

    # Signing status
    result.status, result.signers = _analyze_signing_status(psbt)

    # Return as dict for Chaquopy bridge compatibility
    # (Chaquopy's PyObject.asMap() doesn't work on dataclasses)
    return {
        "inputs": result.inputs,
        "outputs": result.outputs,
        "fee": result.fee,
        "status": result.status,
        "signers": result.signers,
    }


def _is_change_output(out_scope, input_fingerprints: set) -> bool:
    """Detect if an output is a change output.

    Change = output has BIP32 derivation with a fingerprint matching an input,
    AND the derivation path follows a change pattern (.../1/x).
    """
    # Check standard BIP32 derivations
    for pub, deriv in out_scope.bip32_derivations.items():
        if deriv.fingerprint in input_fingerprints:
            path = deriv.derivation
            # Change path pattern: last two indices are [1, x] where 1 = change
            if len(path) >= 2 and path[-2] == 1:
                return True

    # Check taproot BIP32 derivations
    for pub, (leaf_hashes, deriv) in out_scope.taproot_bip32_derivations.items():
        if deriv.fingerprint in input_fingerprints:
            path = deriv.derivation
            if len(path) >= 2 and path[-2] == 1:
                return True

    return False


def _analyze_signing_status(psbt: PSBT) -> tuple:
    """Determine signing status and list signers with their signed/pending state.

    Returns:
        (status_str, signers_list)
    """
    all_fingerprints = {}  # fingerprint -> {"signed": bool for each input}

    for inp_scope in psbt.inputs:
        signed_pubs = set(inp_scope.partial_sigs.keys()) if inp_scope.partial_sigs else set()
        has_tap_sig = bool(inp_scope.taproot_key_sig) if hasattr(inp_scope, 'taproot_key_sig') else False

        for pub, deriv in inp_scope.bip32_derivations.items():
            fp = deriv.fingerprint.hex()
            if fp not in all_fingerprints:
                all_fingerprints[fp] = {"fingerprint": fp, "signed": False}
            if pub in signed_pubs:
                all_fingerprints[fp]["signed"] = True

        for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
            fp = deriv.fingerprint.hex()
            if fp not in all_fingerprints:
                all_fingerprints[fp] = {"fingerprint": fp, "signed": False}
            if has_tap_sig:
                all_fingerprints[fp]["signed"] = True

    signers = list(all_fingerprints.values())

    if not signers:
        status = "unsigned"
    elif all(s["signed"] for s in signers):
        status = "fully_signed"
    elif any(s["signed"] for s in signers):
        status = "partially_signed"
    else:
        status = "unsigned"

    return status, signers
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
python -m pytest tests/test_psbt_parser.py -v
```
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/python/remotesigner/psbt_parser.py tests/test_psbt_parser.py tests/conftest.py requirements-dev.txt
git commit -m "feat: PSBT parser with change detection and signing status"
```

---

### Task 4: Broadcaster Module

**Files:**
- Create: `app/src/main/python/remotesigner/broadcaster.py`
- Create: `tests/test_broadcaster.py`

- [ ] **Step 1: Write failing tests**

```python
# tests/test_broadcaster.py
import pytest
from unittest.mock import patch, MagicMock
from remotesigner.broadcaster import broadcast_transaction


class TestBroadcaster:
    @patch("remotesigner.broadcaster.requests.post")
    def test_broadcasts_to_primary_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="abc123txid")
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "ok"
        assert result["txid"] == "abc123txid"
        mock_post.assert_called_once()
        assert "mempool.space" in mock_post.call_args[0][0]

    @patch("remotesigner.broadcaster.requests.post")
    def test_falls_back_to_secondary(self, mock_post):
        # Primary fails, secondary succeeds
        mock_post.side_effect = [
            MagicMock(status_code=500, text="error"),  # primary retry 1
            MagicMock(status_code=500, text="error"),  # primary retry 2
            MagicMock(status_code=200, text="txid456"),  # fallback
        ]
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "ok"
        assert result["txid"] == "txid456"

    @patch("remotesigner.broadcaster.requests.post")
    def test_returns_raw_hex_on_total_failure(self, mock_post):
        mock_post.return_value = MagicMock(status_code=500, text="server error")
        result = broadcast_transaction("deadbeef")
        assert result["status"] == "error"
        assert result["raw_hex"] == "deadbeef"

    @patch("remotesigner.broadcaster.requests.post")
    def test_uses_testnet_endpoint(self, mock_post):
        mock_post.return_value = MagicMock(status_code=200, text="txid789")
        result = broadcast_transaction("deadbeef", network="test")
        assert "testnet" in mock_post.call_args[0][0]
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
python -m pytest tests/test_broadcaster.py -v
```
Expected: FAIL — `ImportError`

- [ ] **Step 3: Implement broadcaster.py**

```python
# app/src/main/python/remotesigner/broadcaster.py
"""Broadcast signed Bitcoin transactions to the network."""

import requests

ENDPOINTS = {
    "main": [
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    ],
    "test": [
        "https://mempool.space/testnet/api/tx",
        "https://blockstream.info/testnet/api/tx",
    ],
}

TIMEOUT = 10  # seconds


def broadcast_transaction(raw_hex: str, network: str = "main") -> dict:
    """Broadcast a raw transaction hex to the Bitcoin network.

    Tries primary endpoint with one retry, then falls back to secondary.

    Args:
        raw_hex: The signed transaction in hex format.
        network: "main" or "test".

    Returns:
        {"status": "ok", "txid": "..."} on success.
        {"status": "error", "message": "...", "raw_hex": "..."} on failure.
    """
    endpoints = ENDPOINTS.get(network, ENDPOINTS["main"])
    last_error = ""

    for url in endpoints:
        for attempt in range(2):  # one retry per endpoint
            try:
                resp = requests.post(url, data=raw_hex, timeout=TIMEOUT)
                if resp.status_code == 200:
                    return {"status": "ok", "txid": resp.text.strip()}
                last_error = f"{url}: HTTP {resp.status_code} - {resp.text.strip()}"
            except requests.RequestException as e:
                last_error = f"{url}: {e}"

    return {
        "status": "error",
        "message": last_error,
        "raw_hex": raw_hex,
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
python -m pytest tests/test_broadcaster.py -v
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/broadcaster.py tests/test_broadcaster.py
git commit -m "feat: transaction broadcaster with retry and fallback"
```

---

## Chunk 3: Python USB Transport, TrezorUi, and Signer

### Task 5: Custom USB Transport

**Files:**
- Create: `app/src/main/python/remotesigner/usb_transport.py`
- Create: `tests/test_usb_transport.py`

This module creates a custom trezorlib `Transport` subclass that delegates USB I/O to a Kotlin bridge object.

**Important:** The exact trezorlib Transport API must be verified during Task 2 (dependency validation). The implementation below is based on the current main branch API where Transport directly has write_chunk/read_chunk. If the pinned version (0.13.9) uses the older Handle/Protocol architecture, adapt accordingly.

- [ ] **Step 1: Write failing tests with a mock bridge**

```python
# tests/test_usb_transport.py
import pytest
from unittest.mock import MagicMock
from remotesigner.usb_transport import AndroidTransport


class MockBridge:
    """Simulates the Kotlin UsbBridge object."""

    def __init__(self):
        self.written_chunks = []
        self.read_queue = []

    def writeChunk(self, data):
        """Called by Python, implemented in Kotlin."""
        self.written_chunks.append(bytes(data))

    def readChunk(self):
        """Called by Python, implemented in Kotlin."""
        if self.read_queue:
            return self.read_queue.pop(0)
        return bytes(64)

    def open(self):
        pass

    def close(self):
        pass


class TestAndroidTransport:
    def test_write_chunk_delegates_to_bridge(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        transport.open()
        chunk = bytes(64)
        transport.write_chunk(chunk)
        assert bridge.written_chunks == [chunk]

    def test_read_chunk_delegates_to_bridge(self):
        bridge = MockBridge()
        expected = bytes(range(64))
        bridge.read_queue.append(expected)
        transport = AndroidTransport(bridge)
        transport.open()
        result = transport.read_chunk()
        assert result == expected

    def test_chunk_size_is_64(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        assert transport.CHUNK_SIZE == 64

    def test_open_close_lifecycle(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        assert not transport.is_open()
        transport.open()
        assert transport.is_open()
        transport.close()
        assert not transport.is_open()

    def test_context_manager(self):
        bridge = MockBridge()
        transport = AndroidTransport(bridge)
        with transport:
            assert transport.is_open()
        assert not transport.is_open()
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
python -m pytest tests/test_usb_transport.py -v
```
Expected: FAIL — `ImportError`

- [ ] **Step 3: Implement usb_transport.py**

```python
# app/src/main/python/remotesigner/usb_transport.py
"""Custom trezorlib Transport that bridges to Android USB via Kotlin callbacks.

The Kotlin UsbBridge object provides:
  - writeChunk(ByteArray[64]) -> None
  - readChunk() -> ByteArray[64]
  - open() -> None
  - close() -> None

This Transport subclass delegates all USB I/O to that bridge.
Protocol V1 message framing is handled by trezorlib internally.
"""

from trezorlib.transport import Transport


class AndroidTransport(Transport):
    """trezorlib Transport backed by a Kotlin UsbBridge object."""

    PATH_PREFIX = "android"
    CHUNK_SIZE = 64
    ENABLED = True

    def __init__(self, bridge):
        """
        Args:
            bridge: Kotlin UsbBridge object with writeChunk/readChunk methods.
                    Passed from Kotlin via Chaquopy.
        """
        super().__init__()
        self._bridge = bridge
        self._is_open = False

    def get_path(self) -> str:
        return f"{self.PATH_PREFIX}:usb"

    def is_open(self) -> bool:
        return self._is_open

    def _open(self) -> None:
        self._bridge.open()
        self._is_open = True

    def _close(self) -> None:
        self._bridge.close()
        self._is_open = False

    def write_chunk(self, chunk: bytes, /) -> None:
        assert len(chunk) == self.CHUNK_SIZE, f"Expected {self.CHUNK_SIZE} bytes, got {len(chunk)}"
        # Chaquopy auto-converts bytes to Java byte[] and back
        self._bridge.writeChunk(chunk)

    def read_chunk(self, *, timeout: float | None = None) -> bytes:
        # Timeout is not easily supported through the bridge;
        # the Kotlin side handles USB timeouts internally.
        data = self._bridge.readChunk()
        # Ensure we always return exactly CHUNK_SIZE bytes
        if isinstance(data, (bytes, bytearray)):
            return bytes(data).ljust(self.CHUNK_SIZE, b'\x00')[:self.CHUNK_SIZE]
        # Chaquopy may return a Java byte array
        return bytes(data).ljust(self.CHUNK_SIZE, b'\x00')[:self.CHUNK_SIZE]
```

**Note:** If the pinned trezorlib version uses the older Handle/Protocol architecture instead of direct Transport subclassing, replace this with a Handle subclass wrapped in a Transport+ProtocolV1. The dependency validation (Task 2) will reveal which API is needed.

- [ ] **Step 4: Run tests**

```bash
python -m pytest tests/test_usb_transport.py -v
```
Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/usb_transport.py tests/test_usb_transport.py
git commit -m "feat: custom Android USB transport for trezorlib"
```

---

### Task 6: Trezor UI Callbacks

**Files:**
- Create: `app/src/main/python/remotesigner/trezor_ui.py`

- [ ] **Step 1: Implement trezor_ui.py**

```python
# app/src/main/python/remotesigner/trezor_ui.py
"""Trezor UI callbacks for Safe 3 (on-device PIN/passphrase only).

Adapts trezorlib's UI callback mechanism to forward status updates
to the Kotlin UI layer via a status_callback function.

NOTE: The exact API depends on the trezorlib version:
- Newer (main branch): Uses AppManifest with button_callback/pin_callback
- Older (0.13.x): Uses TrezorClient(ui=...) with get_pin/get_passphrase/button_request

This module provides both interfaces. Use whichever matches the installed version.
"""

from trezorlib import messages


class AndroidTrezorUi:
    """UI handler for trezorlib TrezorClient (older 0.13.x API).

    Safe 3 uses on-device PIN and passphrase, so get_pin() should never
    be called. If it is, we raise an error rather than silently failing.
    """

    def __init__(self, status_callback=None):
        """
        Args:
            status_callback: Optional callable(str) to send status updates
                             to the Kotlin UI. Called with messages like
                             "confirm_on_device", "pin_requested", etc.
        """
        self._callback = status_callback

    def _notify(self, message):
        """Send status to Kotlin. Callback is a Java object with onStatus method."""
        if self._callback:
            self._callback.onStatus(message)

    def get_pin(self, code=None):
        """Called when Trezor requests PIN entry.
        Safe 3 uses on-device PIN — this should not be called."""
        self._notify("pin_requested")
        raise RuntimeError(
            "Host-side PIN entry is not supported. "
            "Trezor Safe 3 uses on-device PIN. "
            "Please check your Trezor settings."
        )

    def get_passphrase(self, available_on_device=True):
        """Called when Trezor requests passphrase.
        We always signal on-device entry for Safe 3."""
        self._notify("passphrase_on_device")
        # Return empty string and signal on-device passphrase
        # The exact mechanism depends on trezorlib version.
        # For 0.13.x, returning "" with on_device flag.
        from trezorlib.client import PASSPHRASE_ON_DEVICE
        return PASSPHRASE_ON_DEVICE

    def button_request(self, br=None):
        """Called when Trezor shows a confirmation dialog on screen."""
        self._notify("confirm_on_device")


def create_app_manifest(status_callback=None):
    """Create an AppManifest for newer trezorlib versions (main branch API).

    Use this if TrezorClient uses AppManifest instead of ui= parameter.
    """
    try:
        from trezorlib.client import AppManifest

        def button_cb(msg):
            if status_callback:
                status_callback("confirm_on_device")

        def pin_cb(msg):
            if status_callback:
                status_callback("pin_requested")
            raise RuntimeError("Host-side PIN not supported on Safe 3")

        return AppManifest(
            app_name="RemoteSigner",
            button_callback=button_cb,
            pin_callback=pin_cb,
        )
    except ImportError:
        return None
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/python/remotesigner/trezor_ui.py
git commit -m "feat: Trezor UI callbacks for Safe 3 on-device PIN/passphrase"
```

---

### Task 7: Signer Module (PSBT-to-trezorlib Conversion)

**Files:**
- Create: `app/src/main/python/remotesigner/signer.py`
- Create: `tests/test_signer.py`

This is the most complex module. It ports the PSBT-to-trezorlib conversion logic from HWI's `hwilib/devices/trezor.py`.

- [ ] **Step 1: Write failing tests for the conversion functions**

```python
# tests/test_signer.py
import pytest
from unittest.mock import MagicMock, patch
from embit.psbt import PSBT
from trezorlib import messages
import base64

from remotesigner.signer import (
    psbt_to_trezor_inputs,
    psbt_to_trezor_outputs,
    psbt_to_prev_txes,
    detect_script_type,
    sign_psbt,
)


# Minimal P2WPKH PSBT for testing (you may need to generate a real one)
# For unit tests, we'll mock the PSBT structure
class TestDetectScriptType:
    def test_p2wpkh_native(self):
        """Native segwit v0 (20-byte witness program) -> SPENDWITNESS"""
        # OP_0 <20-byte-hash>
        script = bytes([0x00, 0x14]) + bytes(20)
        result = detect_script_type(script, redeem_script=None, witness_script=None)
        assert result == messages.InputScriptType.SPENDWITNESS

    def test_p2sh_p2wpkh(self):
        """P2SH-wrapped segwit -> SPENDP2SHWITNESS"""
        # P2SH scriptPubKey: OP_HASH160 <20-byte-hash> OP_EQUAL
        p2sh_script = bytes([0xa9, 0x14]) + bytes(20) + bytes([0x87])
        # Redeem script is the witness program: OP_0 <20-byte-hash>
        redeem = bytes([0x00, 0x14]) + bytes(20)
        result = detect_script_type(p2sh_script, redeem_script=redeem, witness_script=None)
        assert result == messages.InputScriptType.SPENDP2SHWITNESS

    def test_p2pkh(self):
        """P2PKH -> SPENDADDRESS"""
        # OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
        script = bytes([0x76, 0xa9, 0x14]) + bytes(20) + bytes([0x88, 0xac])
        result = detect_script_type(script, redeem_script=None, witness_script=None)
        assert result == messages.InputScriptType.SPENDADDRESS

    def test_p2tr(self):
        """Taproot (witness v1, 32-byte program) -> SPENDTAPROOT"""
        # OP_1 <32-byte-x-only-pubkey>
        script = bytes([0x51, 0x20]) + bytes(32)
        result = detect_script_type(script, redeem_script=None, witness_script=None)
        assert result == messages.InputScriptType.SPENDTAPROOT


class TestPsbtToTrezorInputs:
    def test_creates_tx_input_with_derivation(self):
        """Should extract address_n from BIP32 derivation."""
        mock_psbt = MagicMock()
        mock_inp = MagicMock()
        mock_deriv = MagicMock()
        mock_deriv.fingerprint = b'\x12\x34\x56\x78'
        mock_deriv.derivation = [0x80000054, 0x80000000, 0x80000000, 0, 0]
        mock_pub = MagicMock()
        mock_inp.bip32_derivations = {mock_pub: mock_deriv}
        mock_inp.taproot_bip32_derivations = {}
        mock_inp.partial_sigs = {}
        mock_inp.witness_utxo = MagicMock()
        mock_inp.witness_utxo.value = 100000
        mock_inp.witness_utxo.script_pubkey = MagicMock()
        mock_inp.witness_utxo.script_pubkey.data = bytes([0x00, 0x14]) + bytes(20)
        mock_inp.non_witness_utxo = None
        mock_inp.redeem_script = None
        mock_inp.witness_script = None

        mock_psbt.inputs = [mock_inp]
        mock_psbt.tx = MagicMock()
        mock_psbt.tx.vin = [MagicMock()]
        mock_psbt.tx.vin[0].txid = bytes(32)
        mock_psbt.tx.vin[0].vout = 0
        mock_psbt.tx.vin[0].sequence = 0xfffffffd

        master_fp = b'\x12\x34\x56\x78'
        inputs, to_ignore = psbt_to_trezor_inputs(mock_psbt, master_fp)
        assert len(inputs) == 1
        assert inputs[0].address_n == [0x80000054, 0x80000000, 0x80000000, 0, 0]
        assert inputs[0].amount == 100000


class TestSignPsbt:
    @patch("remotesigner.signer.trezorlib_btc")
    def test_sign_returns_complete_for_single_sig(self, mock_btc):
        """When all inputs are signed, status should be 'complete'."""
        # This is an integration-level test that mocks the trezorlib call
        mock_btc.sign_tx.return_value = ([b'\x30\x44' + bytes(68)], b'\x01\x00')
        mock_btc.get_public_node.return_value = MagicMock()
        # Full test would need a real PSBT; for now just verify the interface
        # Detailed integration testing happens on-device (Task 12)
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
python -m pytest tests/test_signer.py -v
```
Expected: FAIL — `ImportError`

- [ ] **Step 3: Implement signer.py — script type detection**

```python
# app/src/main/python/remotesigner/signer.py
"""Sign PSBTs using trezorlib with a Trezor hardware wallet.

Ports the PSBT-to-trezorlib conversion logic from HWI's
hwilib/devices/trezor.py. Reference:
https://github.com/bitcoin-core/HWI/blob/master/hwilib/devices/trezor.py
"""

from trezorlib import messages
from trezorlib import btc as trezorlib_btc
from embit.psbt import PSBT
# embit's finalize may be in different locations depending on version
try:
    from embit.finalizer import finalize_psbt
except ImportError:
    # Fallback: check if PSBT has a finalize method
    finalize_psbt = None

from .usb_transport import AndroidTransport
from .trezor_ui import AndroidTrezorUi


# --- Script analysis helpers ---

def is_p2sh(script: bytes) -> bool:
    """OP_HASH160 <20-byte-hash> OP_EQUAL"""
    return len(script) == 23 and script[0] == 0xa9 and script[1] == 0x14 and script[22] == 0x87


def is_p2pkh(script: bytes) -> bool:
    """OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG"""
    return len(script) == 25 and script[0] == 0x76 and script[1] == 0xa9 and script[2] == 0x14


def is_witness(script: bytes) -> tuple:
    """Check if script is a witness program.

    Returns: (is_witness, version, program_bytes)
    """
    if len(script) < 4 or len(script) > 42:
        return False, 0, b""
    version_byte = script[0]
    if version_byte == 0x00:
        version = 0
    elif 0x51 <= version_byte <= 0x60:
        version = version_byte - 0x50
    else:
        return False, 0, b""
    prog_len = script[1]
    if prog_len + 2 != len(script):
        return False, 0, b""
    if prog_len < 2 or prog_len > 40:
        return False, 0, b""
    return True, version, script[2:]


def detect_script_type(
    script_pubkey: bytes,
    redeem_script: bytes | None,
    witness_script: bytes | None,
) -> messages.InputScriptType:
    """Determine the trezorlib InputScriptType from script analysis.

    Follows the same logic as HWI's trezor.py.
    """
    scriptcode = script_pubkey
    p2sh = False

    # Peel P2SH layer
    if is_p2sh(scriptcode) and redeem_script:
        scriptcode = redeem_script
        p2sh = True

    # Check for witness
    wit, wit_ver, wit_prog = is_witness(scriptcode)

    if wit and wit_ver == 0 and p2sh:
        return messages.InputScriptType.SPENDP2SHWITNESS
    elif wit and wit_ver == 0 and not p2sh:
        return messages.InputScriptType.SPENDWITNESS
    elif wit and wit_ver == 1:
        return messages.InputScriptType.SPENDTAPROOT
    elif is_p2pkh(scriptcode):
        return messages.InputScriptType.SPENDADDRESS
    else:
        return messages.InputScriptType.SPENDADDRESS  # fallback


# --- Output script type mapping ---

OUTPUT_SCRIPT_TYPE_MAP = {
    messages.InputScriptType.SPENDADDRESS: messages.OutputScriptType.PAYTOADDRESS,
    messages.InputScriptType.SPENDP2SHWITNESS: messages.OutputScriptType.PAYTOP2SHWITNESS,
    messages.InputScriptType.SPENDWITNESS: messages.OutputScriptType.PAYTOWITNESS,
    messages.InputScriptType.SPENDTAPROOT: messages.OutputScriptType.PAYTOTAPROOT,
    messages.InputScriptType.SPENDMULTISIG: messages.OutputScriptType.PAYTOMULTISIG,
}


# --- PSBT to trezorlib conversion ---

def psbt_to_trezor_inputs(psbt: PSBT, master_fp: bytes) -> tuple:
    """Convert PSBT inputs to trezorlib TxInputType list.

    Args:
        psbt: Parsed PSBT object (embit).
        master_fp: 4-byte master fingerprint of the connected Trezor.

    Returns:
        (inputs: list[TxInputType], to_ignore: list[int])
        to_ignore contains indices of inputs not signable by this device.
    """
    inputs = []
    to_ignore = []

    for i, inp_scope in enumerate(psbt.inputs):
        vin = psbt.tx.vin[i]
        # NOTE: embit may store txid in display (big-endian) or internal (little-endian) order.
        # trezorlib expects internal byte order (same as Bitcoin Core's uint256).
        # Verify during integration testing. If embit uses display order, reverse:
        #   prev_hash = bytes(reversed(vin.txid))
        # If embit uses internal order (likely), use directly:
        prev_hash = vin.txid

        tx_input = messages.TxInputType(
            prev_hash=prev_hash,
            prev_index=vin.vout,
            sequence=vin.sequence,
        )

        # Get UTXO for amount and script
        utxo = None
        if inp_scope.witness_utxo:
            utxo = inp_scope.witness_utxo
        elif inp_scope.non_witness_utxo:
            utxo = inp_scope.non_witness_utxo.vout[vin.vout]

        if utxo:
            tx_input.amount = utxo.value
            script_pubkey_bytes = bytes(utxo.script_pubkey.data)
        else:
            tx_input.amount = 0
            script_pubkey_bytes = b""

        # Determine script type
        redeem = bytes(inp_scope.redeem_script.data) if inp_scope.redeem_script else None
        witness_sc = bytes(inp_scope.witness_script.data) if inp_scope.witness_script else None
        tx_input.script_type = detect_script_type(script_pubkey_bytes, redeem, witness_sc)

        # Find our signing key via BIP32 derivations
        found = False

        # Check standard derivations (ECDSA)
        for pub, deriv in inp_scope.bip32_derivations.items():
            if deriv.fingerprint == master_fp:
                if pub in (inp_scope.partial_sigs or {}):
                    continue  # already signed by this key
                tx_input.address_n = list(deriv.derivation)
                found = True
                break

        # Check taproot derivations
        if not found:
            for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
                if deriv.fingerprint == master_fp:
                    tx_input.address_n = list(deriv.derivation)
                    found = True
                    break

        # Handle multisig
        if inp_scope.witness_script:
            multisig = _parse_multisig(inp_scope, psbt)
            if multisig:
                tx_input.multisig = multisig

        if not found:
            # This input doesn't belong to our device — use dummy derivation
            tx_input.address_n = [0x80000000 | 84, 0x80000000, 0x80000000, 0, 0]
            tx_input.script_type = messages.InputScriptType.SPENDWITNESS
            tx_input.multisig = None
            to_ignore.append(i)

        inputs.append(tx_input)

    return inputs, to_ignore


def psbt_to_trezor_outputs(psbt: PSBT, master_fp: bytes, network: str = "main") -> list:
    """Convert PSBT outputs to trezorlib TxOutputType list.

    Change outputs (matching master_fp) get address_n set instead of address.
    """
    from embit.networks import NETWORKS

    outputs = []
    net = NETWORKS[network]

    for i, out_scope in enumerate(psbt.outputs):
        vout = psbt.tx.vout[i]
        tx_output = messages.TxOutputType(amount=vout.value)

        # Check if this is a change output (our device's key)
        is_change = False

        # Standard derivations
        for pub, deriv in out_scope.bip32_derivations.items():
            if deriv.fingerprint == master_fp:
                tx_output.address_n = list(deriv.derivation)
                # Determine output script type from the output's script
                script_bytes = bytes(vout.script_pubkey.data)
                redeem = bytes(out_scope.redeem_script.data) if out_scope.redeem_script else None
                inp_type = detect_script_type(script_bytes, redeem, None)
                tx_output.script_type = OUTPUT_SCRIPT_TYPE_MAP.get(
                    inp_type, messages.OutputScriptType.PAYTOADDRESS
                )
                is_change = True
                break

        # Taproot derivations
        if not is_change:
            for pub, (leaf_hashes, deriv) in out_scope.taproot_bip32_derivations.items():
                if deriv.fingerprint == master_fp:
                    tx_output.address_n = list(deriv.derivation)
                    tx_output.script_type = messages.OutputScriptType.PAYTOTAPROOT
                    is_change = True
                    break

        # External output — set address
        if not is_change:
            try:
                tx_output.address = vout.script_pubkey.address(net)
            except Exception:
                tx_output.address = ""
            tx_output.script_type = messages.OutputScriptType.PAYTOADDRESS

        # Handle multisig on outputs
        if out_scope.witness_script:
            multisig = _parse_multisig_output(out_scope, psbt)
            if multisig:
                tx_output.multisig = multisig

        outputs.append(tx_output)

    return outputs


def psbt_to_prev_txes(psbt: PSBT) -> dict:
    """Extract previous transactions from PSBT for Trezor verification.

    Returns dict mapping txid (bytes) -> TransactionType.
    Only includes non_witness_utxo entries (segwit-only inputs don't need this).
    """
    prev_txes = {}

    for i, inp_scope in enumerate(psbt.inputs):
        prev_tx = inp_scope.non_witness_utxo
        if prev_tx is None:
            continue

        txid = prev_tx.txid()
        if txid in prev_txes:
            continue

        t = messages.TransactionType()
        t.version = prev_tx.version
        t.lock_time = prev_tx.locktime

        t.inputs = []
        for vin in prev_tx.vin:
            t.inputs.append(messages.TxInputType(
                prev_hash=vin.txid,
                prev_index=vin.vout,
                script_sig=bytes(vin.script_sig.data) if vin.script_sig else b"",
                sequence=vin.sequence,
            ))

        t.bin_outputs = []
        for vout in prev_tx.vout:
            t.bin_outputs.append(messages.TxOutputBinType(
                amount=vout.value,
                script_pubkey=bytes(vout.script_pubkey.data),
            ))

        prev_txes[txid] = t

    return prev_txes


def _parse_multisig(inp_scope, psbt) -> messages.MultisigRedeemScriptType | None:
    """Parse multisig structure from a PSBT input's witness_script or redeem_script.

    Reference: HWI's parse_multisig() function.
    Returns MultisigRedeemScriptType or None if not multisig.
    """
    script = None
    if inp_scope.witness_script:
        script = bytes(inp_scope.witness_script.data)
    elif inp_scope.redeem_script:
        script = bytes(inp_scope.redeem_script.data)

    if not script:
        return None

    return _parse_multisig_script(script, inp_scope.bip32_derivations, psbt)


def _parse_multisig_output(out_scope, psbt) -> messages.MultisigRedeemScriptType | None:
    """Parse multisig from output scope."""
    script = None
    if out_scope.witness_script:
        script = bytes(out_scope.witness_script.data)
    elif out_scope.redeem_script:
        script = bytes(out_scope.redeem_script.data)

    if not script:
        return None

    return _parse_multisig_script(script, out_scope.bip32_derivations, psbt)


def _parse_multisig_script(script: bytes, bip32_derivations: dict, psbt) -> messages.MultisigRedeemScriptType | None:
    """Parse a bare multisig script (OP_m <pubkeys> OP_n OP_CHECKMULTISIG).

    Returns MultisigRedeemScriptType or None if not a valid multisig script.
    """
    if len(script) < 37:  # minimum: OP_1 <33-byte-key> OP_1 OP_CHECKMULTISIG
        return None

    # Read m
    if not (0x51 <= script[0] <= 0x60):  # OP_1 through OP_16
        return None
    m = script[0] - 0x50

    # Extract pubkeys
    offset = 1
    pubkeys_raw = []
    while offset < len(script) and script[offset] == 0x21:  # 33-byte push
        offset += 1
        pubkeys_raw.append(script[offset:offset + 33])
        offset += 33

    if not pubkeys_raw:
        return None

    # Read n
    if offset >= len(script) or not (0x51 <= script[offset] <= 0x60):
        return None
    n = script[offset] - 0x50
    offset += 1

    # Verify OP_CHECKMULTISIG
    if offset >= len(script) or script[offset] != 0xae:
        return None

    if n != len(pubkeys_raw):
        return None

    # Build HDNodePathType entries
    hd_pubkeys = []
    for raw_pub in pubkeys_raw:
        # Default: dummy node with just the raw public key
        node = messages.HDNodeType(
            depth=0,
            fingerprint=0,
            child_num=0,
            chain_code=bytes(32),
            public_key=raw_pub,
        )
        address_n = []

        # Try to resolve against BIP32 derivations
        for pub, deriv in bip32_derivations.items():
            pub_bytes = pub.sec() if hasattr(pub, 'sec') else bytes(pub)
            if pub_bytes == raw_pub:
                address_n = list(deriv.derivation)
                # Try to find the corresponding xpub in the PSBT global xpubs
                # to populate the node with real HD key data
                if hasattr(psbt, 'xpubs') and psbt.xpubs:
                    for xpub_key, xpub_origin in psbt.xpubs.items():
                        if xpub_origin.fingerprint == deriv.fingerprint:
                            origin_path = list(xpub_origin.derivation)
                            if deriv.derivation[:len(origin_path)] == origin_path:
                                address_n = list(deriv.derivation[len(origin_path):])
                                # Populate node from xpub
                                node = messages.HDNodeType(
                                    depth=xpub_key.depth if hasattr(xpub_key, 'depth') else 0,
                                    fingerprint=int.from_bytes(
                                        xpub_key.parent_fingerprint
                                        if hasattr(xpub_key, 'parent_fingerprint')
                                        else b'\x00\x00\x00\x00', 'big'
                                    ),
                                    child_num=xpub_key.child_number if hasattr(xpub_key, 'child_number') else 0,
                                    chain_code=xpub_key.chain_code if hasattr(xpub_key, 'chain_code') else bytes(32),
                                    public_key=xpub_key.sec() if hasattr(xpub_key, 'sec') else raw_pub,
                                )
                                break
                break

        hd_pubkeys.append(messages.HDNodePathType(
            node=node,
            address_n=address_n,
        ))

    return messages.MultisigRedeemScriptType(
        m=m,
        signatures=[b""] * n,
        pubkeys=hd_pubkeys,
    )


# --- Main signing function ---

def sign_psbt(psbt_bytes: bytes, bridge, status_callback=None, network: str = "main") -> dict:
    """Sign a PSBT using a connected Trezor device.

    Args:
        psbt_bytes: Raw PSBT binary data.
        bridge: Kotlin UsbBridge object for USB communication.
        status_callback: Optional callable(str) for status updates.
        network: "main" or "test".

    Returns:
        {"status": "complete", "raw_tx": "hex..."} if fully signed.
        {"status": "partial", "psbt": bytes} if more signatures needed.
        {"status": "error", "message": "..."} on failure.
    """
    try:
        # 1. Parse PSBT
        psbt = PSBT.parse(psbt_bytes)

        # 2. Connect to Trezor
        transport = AndroidTransport(bridge)
        ui = AndroidTrezorUi(status_callback)

        # NOTE: The exact client creation depends on trezorlib version.
        # This uses the 0.13.x API. Adjust if validation reveals different API.
        from trezorlib.client import TrezorClient
        client = TrezorClient(transport=transport, ui=ui)

        # 3. Get master fingerprint from device
        #    Query the master node (empty path) to get root fingerprint.
        #    Alternatively, use the fingerprint from the PSBT's BIP32 derivations
        #    that match any key on the device.
        coin_name = "Testnet" if network == "test" else "Bitcoin"
        master_fp = _get_master_fingerprint(client, coin_name)

        # 4. Convert PSBT to trezorlib args
        inputs, to_ignore = psbt_to_trezor_inputs(psbt, master_fp)
        outputs = psbt_to_trezor_outputs(psbt, master_fp, network=network)
        prev_txes = psbt_to_prev_txes(psbt)

        if status_callback:
            status_callback.onStatus("signing")

        # 5. Sign
        signatures, serialized_tx = trezorlib_btc.sign_tx(
            client,
            coin_name=coin_name,
            inputs=inputs,
            outputs=outputs,
            prev_txes=prev_txes,
        )

        # 6. Insert signatures back into PSBT
        for i, sig in enumerate(signatures):
            if i in to_ignore or sig is None:
                continue
            inp_scope = psbt.inputs[i]

            # ECDSA signatures -> partial_sigs
            for pub, deriv in inp_scope.bip32_derivations.items():
                if deriv.fingerprint == master_fp and pub not in (inp_scope.partial_sigs or {}):
                    if inp_scope.partial_sigs is None:
                        inp_scope.partial_sigs = {}
                    inp_scope.partial_sigs[pub] = sig + b'\x01'  # SIGHASH_ALL
                    break

            # Taproot signatures -> taproot_key_sig
            for pub, (leaf_hashes, deriv) in inp_scope.taproot_bip32_derivations.items():
                if deriv.fingerprint == master_fp:
                    inp_scope.taproot_key_sig = sig  # No sighash appended for Schnorr
                    break

        # 7. Check completeness
        final_tx = finalize_psbt(psbt)
        if final_tx is not None:
            return {"status": "complete", "raw_tx": final_tx.serialize().hex()}
        else:
            return {"status": "partial", "psbt": psbt.serialize()}

    except Exception as e:
        return {"status": "error", "message": str(e)}


def _get_master_fingerprint(client, coin_name: str) -> bytes:
    """Get the master key fingerprint from the Trezor.

    Queries get_public_node at the master level (empty derivation path)
    and computes HASH160(public_key)[:4] as per BIP32.

    If root_fingerprint is available directly on the response, use that.
    """
    import hashlib

    # Query master node
    node_resp = trezorlib_btc.get_public_node(client, [], coin_name=coin_name)

    # Try direct root_fingerprint field (newer trezorlib versions)
    if hasattr(node_resp, 'root_fingerprint') and node_resp.root_fingerprint:
        fp = node_resp.root_fingerprint
        if isinstance(fp, int):
            return fp.to_bytes(4, 'big')
        return bytes(fp)[:4]

    # Fallback: compute HASH160 of the master public key
    pub_key = bytes(node_resp.node.public_key)
    sha = hashlib.sha256(pub_key).digest()
    h160 = hashlib.new('ripemd160', sha).digest()
    return h160[:4]
```

- [ ] **Step 4: Run tests**

```bash
python -m pytest tests/test_signer.py -v
```
Expected: Script type detection tests PASS. Integration tests may need refinement based on actual embit/trezorlib API.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/python/remotesigner/signer.py tests/test_signer.py
git commit -m "feat: PSBT-to-trezorlib signer with script type detection and multisig"
```

---

## Chunk 4: Kotlin USB Bridge and ViewModel

### Task 8: Kotlin USB Bridge

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/usb/UsbBridge.kt`
- Create: `app/src/main/kotlin/com/remotesigner/usb/TrezorUsbManager.kt`

- [ ] **Step 1: Implement TrezorUsbManager.kt**

Handles USB permission requests and device discovery.

```kotlin
// app/src/main/kotlin/com/remotesigner/usb/TrezorUsbManager.kt
package com.remotesigner.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class TrezorUsbManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.remotesigner.USB_PERMISSION"
        // Trezor Safe 3 USB IDs
        const val TREZOR_VENDOR_ID = 0x1209
        const val TREZOR_PRODUCT_ID_T3B1 = 0x53C1  // Safe 3 bootloader
        const val TREZOR_PRODUCT_ID_T3B1_FW = 0x53C0 // Safe 3 firmware
        // Also support other Trezor models for forward compatibility
        val TREZOR_PRODUCT_IDS = setOf(0x53C0, 0x53C1, 0x53B0, 0x53B1, 0x53A0, 0x53A1, 0x01)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findTrezorDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == TREZOR_VENDOR_ID &&
                device.productId in TREZOR_PRODUCT_IDS
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    callback(granted)
                    context.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    fun openDevice(device: UsbDevice): UsbBridge? {
        val connection = usbManager.openDevice(device) ?: return null
        return UsbBridge(device, connection)
    }
}
```

- [ ] **Step 2: Implement UsbBridge.kt**

The bridge object that gets passed to Python for USB I/O.

```kotlin
// app/src/main/kotlin/com/remotesigner/usb/UsbBridge.kt
package com.remotesigner.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * USB bridge for Trezor communication. Passed to Python via Chaquopy.
 *
 * Provides writeChunk/readChunk methods that Python's AndroidTransport calls.
 * Uses UsbRequest for interrupt endpoint transfers (not bulkTransfer).
 *
 * Trezor uses interrupt endpoints:
 *   - OUT endpoint 0x01 for writes
 *   - IN endpoint 0x81 for reads
 */
class UsbBridge(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var isOpen = false

    companion object {
        const val CHUNK_SIZE = 64
        const val TIMEOUT_MS = 5000L
    }

    /**
     * Called from Python: open the USB interface and claim it.
     */
    fun open() {
        if (isOpen) return

        // Find the HID interface (class 3)
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                usbInterface = intf
                break
            }
        }

        val intf = usbInterface
            ?: throw IllegalStateException("No HID interface found on Trezor device")

        if (!connection.claimInterface(intf, true)) {
            throw IllegalStateException("Failed to claim USB interface")
        }

        // Find interrupt endpoints
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep
                } else {
                    endpointOut = ep
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            throw IllegalStateException(
                "Could not find interrupt endpoints (IN: $endpointIn, OUT: $endpointOut)"
            )
        }

        isOpen = true
    }

    /**
     * Called from Python: close the USB interface.
     */
    fun close() {
        if (!isOpen) return
        usbInterface?.let { connection.releaseInterface(it) }
        connection.close()
        isOpen = false
    }

    /**
     * Called from Python: write a 64-byte chunk to the Trezor.
     */
    @Synchronized
    fun writeChunk(data: ByteArray) {
        val ep = endpointOut
            ?: throw IllegalStateException("USB not open: no OUT endpoint")

        require(data.size == CHUNK_SIZE) { "Expected $CHUNK_SIZE bytes, got ${data.size}" }

        val request = UsbRequest()
        try {
            if (!request.initialize(connection, ep)) {
                throw IllegalStateException("Failed to initialize USB write request")
            }
            val buffer = ByteBuffer.wrap(data)
            if (!request.queue(buffer)) {
                throw IllegalStateException("Failed to queue USB write request")
            }
            val completed = connection.requestWait(TIMEOUT_MS)
            if (completed != request) {
                throw IllegalStateException("USB write request failed or timed out")
            }
        } finally {
            request.close()
        }
    }

    /**
     * Called from Python: read a 64-byte chunk from the Trezor.
     */
    @Synchronized
    fun readChunk(): ByteArray {
        val ep = endpointIn
            ?: throw IllegalStateException("USB not open: no IN endpoint")

        val request = UsbRequest()
        try {
            if (!request.initialize(connection, ep)) {
                throw IllegalStateException("Failed to initialize USB read request")
            }
            val buffer = ByteBuffer.allocate(CHUNK_SIZE)
            if (!request.queue(buffer)) {
                throw IllegalStateException("Failed to queue USB read request")
            }
            val completed = connection.requestWait(TIMEOUT_MS)
            if (completed != request) {
                throw IllegalStateException("USB read request failed or timed out")
            }
            buffer.rewind()
            val result = ByteArray(CHUNK_SIZE)
            buffer.get(result)
            return result
        } finally {
            request.close()
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/usb/
git commit -m "feat: Kotlin USB bridge with interrupt endpoint transfers for Trezor"
```

---

### Task 9: Python Bridge and ViewModel

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt`
- Create: `app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt`

- [ ] **Step 1: Implement PythonBridge.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/bridge/PythonBridge.kt
package com.remotesigner.bridge

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.remotesigner.usb.UsbBridge

/**
 * Bridge between Kotlin and the Python remotesigner package.
 * All calls are blocking and must be run on a background thread.
 */
class PythonBridge {

    private val py = Python.getInstance()
    private val parserModule: PyObject = py.getModule("remotesigner.psbt_parser")
    private val signerModule: PyObject = py.getModule("remotesigner.signer")
    private val broadcasterModule: PyObject = py.getModule("remotesigner.broadcaster")

    /**
     * Parse a PSBT and return structured transaction data.
     */
    fun parsePsbt(psbtBytes: ByteArray): Map<String, Any?> {
        val result = parserModule.callAttr("parse_psbt", psbtBytes)
        return pyObjectToMap(result)
    }

    /**
     * Sign a PSBT using the connected Trezor.
     *
     * NOTE: Chaquopy does not auto-convert Kotlin lambdas to Python callables.
     * We use a Java interface that Python can call via Chaquopy's Java interop.
     */
    fun signPsbt(
        psbtBytes: ByteArray,
        bridge: UsbBridge,
        statusCallback: (String) -> Unit,
        network: String = "main",
    ): Map<String, Any?> {
        // Use a StatusCallback interface that Python can call
        val callback = object : StatusCallback {
            override fun onStatus(status: String) {
                statusCallback(status)
            }
        }
        val result = signerModule.callAttr(
            "sign_psbt", psbtBytes, bridge, callback, network
        )
        return pyObjectToMap(result)
    }

    /**
     * Interface for status callbacks from Python.
     * Python calls callback.onStatus("message") via Chaquopy's Java interop.
     */
    interface StatusCallback {
        fun onStatus(status: String)
    }

    /**
     * Broadcast a signed transaction.
     */
    fun broadcast(rawHex: String, network: String = "main"): Map<String, Any?> {
        val result = broadcasterModule.callAttr("broadcast_transaction", rawHex, network)
        return pyObjectToMap(result)
    }

    private fun pyObjectToMap(obj: PyObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val pyMap = obj.asMap()
        for ((key, value) in pyMap) {
            map[key.toString()] = when {
                value == null -> null
                else -> try { value.toJava(Any::class.java) } catch (e: Exception) { value.toString() }
            }
        }
        return map
    }
}
```

- [ ] **Step 2: Implement SignerViewModel.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/viewmodel/SignerViewModel.kt
package com.remotesigner.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotesigner.bridge.PythonBridge
import com.remotesigner.usb.TrezorUsbManager
import com.remotesigner.usb.UsbBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TxOutput(
    val address: String,
    val amount: Long,
    val isChange: Boolean,
)

data class SignerInfo(
    val fingerprint: String,
    val signed: Boolean,
    val isThisDevice: Boolean = false,
)

sealed class AppState {
    data object Home : AppState()
    data class TransactionReview(
        val outputs: List<TxOutput>,
        val fee: Long,
        val totalSent: Long,
        val status: String,
        val signers: List<SignerInfo>,
        val warnings: List<String>,
    ) : AppState()
    data class Signing(val message: String) : AppState()
    data class Result(
        val isComplete: Boolean,
        val txid: String? = null,
        val rawHex: String? = null,
        val updatedPsbt: ByteArray? = null,
        val broadcastStatus: String? = null,
        val errorMessage: String? = null,
    ) : AppState()
    data class Error(val message: String) : AppState()
}

class SignerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppState>(AppState.Home)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val pythonBridge = PythonBridge()
    val trezorUsb = TrezorUsbManager(application)

    private var currentPsbtBytes: ByteArray? = null
    private var currentUsbBridge: UsbBridge? = null

    fun loadPsbt(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.readBytes()
                        ?: throw IllegalStateException("Could not read file")
                }
                parsePsbt(bytes)
            } catch (e: Exception) {
                _state.value = AppState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    fun loadPsbt(bytes: ByteArray) {
        viewModelScope.launch { parsePsbt(bytes) }
    }

    private suspend fun parsePsbt(bytes: ByteArray) {
        try {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.parsePsbt(bytes)
            }
            currentPsbtBytes = bytes

            @Suppress("UNCHECKED_CAST")
            val outputs = (result["outputs"] as? List<Map<String, Any?>>)?.map { out ->
                TxOutput(
                    address = out["address"]?.toString() ?: "unknown",
                    amount = (out["amount"] as? Number)?.toLong() ?: 0,
                    isChange = out["is_change"] as? Boolean ?: false,
                )
            } ?: emptyList()

            val fee = (result["fee"] as? Number)?.toLong() ?: 0
            val totalSent = outputs.filter { !it.isChange }.sumOf { it.amount }

            @Suppress("UNCHECKED_CAST")
            val signers = (result["signers"] as? List<Map<String, Any?>>)?.map { s ->
                SignerInfo(
                    fingerprint = s["fingerprint"]?.toString() ?: "",
                    signed = s["signed"] as? Boolean ?: false,
                )
            } ?: emptyList()

            val warnings = mutableListOf<String>()
            if (outputs.any { !it.isChange } && outputs.none { it.isChange }) {
                // All outputs are external — could be missing derivation data
            }
            if (fee > 1_000_000) { // > 0.01 BTC
                warnings.add("Fee is unusually high: ${fee / 100_000_000.0} BTC")
            }

            _state.value = AppState.TransactionReview(
                outputs = outputs,
                fee = fee,
                totalSent = totalSent,
                status = result["status"]?.toString() ?: "unknown",
                signers = signers,
                warnings = warnings,
            )
        } catch (e: Exception) {
            _state.value = AppState.Error("Invalid PSBT: ${e.message}")
        }
    }

    fun signWithTrezor() {
        val psbt = currentPsbtBytes ?: return
        val device = trezorUsb.findTrezorDevice()

        if (device == null) {
            _state.value = AppState.Signing("Connect Trezor via USB-C cable")
            return
        }

        if (!trezorUsb.hasPermission(device)) {
            trezorUsb.requestPermission(device) { granted ->
                if (granted) signWithTrezor()
                else _state.value = AppState.Error("USB permission denied")
            }
            return
        }

        _state.value = AppState.Signing("Connecting to Trezor...")

        viewModelScope.launch {
            try {
                val bridge = withContext(Dispatchers.IO) {
                    trezorUsb.openDevice(device)
                        ?: throw IllegalStateException("Failed to open USB device")
                }
                currentUsbBridge = bridge

                val result = withContext(Dispatchers.IO) {
                    pythonBridge.signPsbt(
                        psbtBytes = psbt,
                        bridge = bridge,
                        statusCallback = { status ->
                            viewModelScope.launch {
                                _state.value = AppState.Signing(
                                    when (status) {
                                        "confirm_on_device" -> "Confirm on your Trezor..."
                                        "signing" -> "Signing transaction..."
                                        "pin_requested" -> "Enter PIN on your Trezor..."
                                        "passphrase_on_device" -> "Enter passphrase on your Trezor..."
                                        else -> status
                                    }
                                )
                            }
                        },
                    )
                }

                when (result["status"]) {
                    "complete" -> {
                        _state.value = AppState.Result(
                            isComplete = true,
                            rawHex = result["raw_tx"]?.toString(),
                        )
                    }
                    "partial" -> {
                        _state.value = AppState.Result(
                            isComplete = false,
                            updatedPsbt = (result["psbt"] as? ByteArray),
                        )
                    }
                    else -> {
                        _state.value = AppState.Error(
                            result["message"]?.toString() ?: "Signing failed"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = AppState.Error("Signing error: ${e.message}")
            } finally {
                currentUsbBridge?.close()
                currentUsbBridge = null
            }
        }
    }

    fun broadcast() {
        val state = _state.value
        if (state !is AppState.Result || state.rawHex == null) return

        _state.value = state.copy(broadcastStatus = "Broadcasting...")

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                pythonBridge.broadcast(state.rawHex)
            }

            if (result["status"] == "ok") {
                _state.value = state.copy(
                    txid = result["txid"]?.toString(),
                    broadcastStatus = "Broadcast successful",
                )
            } else {
                _state.value = state.copy(
                    broadcastStatus = "Broadcast failed: ${result["message"]}",
                )
            }
        }
    }

    fun goHome() {
        currentPsbtBytes = null
        _state.value = AppState.Home
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/bridge/ app/src/main/kotlin/com/remotesigner/viewmodel/
git commit -m "feat: Python bridge and ViewModel with signing state machine"
```

---

## Chunk 5: Kotlin UI, Manifest, and Integration

### Task 10: Jetpack Compose UI Screens

**Files:**
- Create: `app/src/main/kotlin/com/remotesigner/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt`
- Create: `app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt`
- Create: `app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt`
- Create: `app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt`
- Create: `app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt`
- Modify: `app/src/main/kotlin/com/remotesigner/MainActivity.kt`

- [ ] **Step 1: Create Theme.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/theme/Theme.kt
package com.remotesigner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme()

@Composable
fun RemoteSignerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
```

- [ ] **Step 2: Create HomeScreen.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/HomeScreen.kt
package com.remotesigner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onPsbtSelected: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onPsbtSelected(it) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Satoshi Signer",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Sign Bitcoin transactions with your Trezor",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open PSBT File")
            }
        }
    }
}
```

- [ ] **Step 3: Create TransactionReviewScreen.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/TransactionReviewScreen.kt
package com.remotesigner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerInfo
import com.remotesigner.viewmodel.TxOutput

@Composable
fun TransactionReviewScreen(
    state: AppState.TransactionReview,
    onSign: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Transaction Details", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Warnings
            state.warnings.forEach { warning ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(warning, modifier = Modifier.padding(12.dp))
                }
            }

            // Sending outputs
            Text("Sending:", style = MaterialTheme.typography.titleMedium)
            state.outputs.filter { !it.isChange }.forEach { out ->
                OutputRow(out, prefix = "\u2192") // →
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Change outputs
            val changeOutputs = state.outputs.filter { it.isChange }
            if (changeOutputs.isNotEmpty()) {
                Text("Change (back to wallet):", style = MaterialTheme.typography.titleMedium)
                changeOutputs.forEach { out ->
                    OutputRow(out, prefix = "\u2190") // ←
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Fee and total
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fee:")
                Text(formatBtc(state.fee))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total sent:", style = MaterialTheme.typography.titleMedium)
                Text(formatBtc(state.totalSent), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Signing status
            Text("Status: ${state.status.replace('_', ' ')}", style = MaterialTheme.typography.titleMedium)
            if (state.signers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.signers.forEach { signer ->
                    SignerRow(signer)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Button(
                onClick = onSign,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign with Trezor")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun OutputRow(output: TxOutput, prefix: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "$prefix ${shortenAddress(output.address)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(formatBtc(output.amount), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignerRow(signer: SignerInfo) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            if (signer.signed) "\u2713" else "\u2717", // ✓ or ✗
            modifier = Modifier.width(24.dp),
        )
        Text(signer.fingerprint)
        if (signer.isThisDevice) {
            Text(" \u2190 this device", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBtc(satoshis: Long): String {
    return "%.8f BTC".format(satoshis / 100_000_000.0)
}

private fun shortenAddress(address: String): String {
    return if (address.length > 20) {
        "${address.take(10)}...${address.takeLast(8)}"
    } else address
}
```

- [ ] **Step 4: Create SigningScreen.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/SigningScreen.kt
package com.remotesigner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SigningScreen(message: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(24.dp))
            Text(message, style = MaterialTheme.typography.titleMedium)
        }
    }
}
```

- [ ] **Step 5: Create ResultScreen.kt**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/ResultScreen.kt
package com.remotesigner.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.remotesigner.viewmodel.AppState

@Composable
fun ResultScreen(
    state: AppState.Result,
    onBroadcast: () -> Unit,
    onExportPsbt: (ByteArray) -> Unit,
    onHome: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var broadcastClicked by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.isComplete) {
                Text("Transaction Signed", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))

                if (state.txid != null) {
                    Text("Broadcast successful!")
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text("txid: ${state.txid}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // Show broadcast button
                    state.broadcastStatus?.let {
                        Text(it)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            broadcastClicked = true
                            onBroadcast()
                        },
                        enabled = !broadcastClicked || state.broadcastStatus?.contains("failed") == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Broadcast Transaction")
                    }
                }

                // Raw hex for manual broadcast
                state.rawHex?.let { hex ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Raw transaction (for manual broadcast):", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(hex)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy Raw Hex to Clipboard")
                    }
                }
            } else {
                // Partial signature
                Text("Signature Added", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("The transaction needs more signatures before it can be broadcast.")
                Spacer(modifier = Modifier.height(16.dp))

                state.updatedPsbt?.let { psbt ->
                    Button(
                        onClick = { onExportPsbt(psbt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export Updated PSBT")
                    }
                }
            }

            state.errorMessage?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onHome,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back to Home")
            }
        }
    }
}
```

- [ ] **Step 6: Create AppNavigation.kt and update MainActivity**

```kotlin
// app/src/main/kotlin/com/remotesigner/ui/AppNavigation.kt
package com.remotesigner.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remotesigner.viewmodel.AppState
import com.remotesigner.viewmodel.SignerViewModel
import java.io.File

@Composable
fun AppRoot(
    viewModel: SignerViewModel = viewModel(),
    intentPsbtBytes: ByteArray? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Handle PSBT from intent
    LaunchedEffect(intentPsbtBytes) {
        intentPsbtBytes?.let { viewModel.loadPsbt(it) }
    }

    when (val s = state) {
        is AppState.Home -> HomeScreen(
            onPsbtSelected = { uri -> viewModel.loadPsbt(uri) },
        )
        is AppState.TransactionReview -> TransactionReviewScreen(
            state = s,
            onSign = { viewModel.signWithTrezor() },
            onCancel = { viewModel.goHome() },
        )
        is AppState.Signing -> SigningScreen(message = s.message)
        is AppState.Result -> ResultScreen(
            state = s,
            onBroadcast = { viewModel.broadcast() },
            onExportPsbt = { psbt ->
                // Share via Android share sheet
                val file = File(context.cacheDir, "signed.psbt")
                file.writeBytes(psbt)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export PSBT"))
            },
            onHome = { viewModel.goHome() },
        )
        is AppState.Error -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Error: ${s.message}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.goHome() }) {
                        Text("Back to Home")
                    }
                }
            }
        }
    }
}
```

Update `MainActivity.kt`:
```kotlin
// app/src/main/kotlin/com/remotesigner/MainActivity.kt
package com.remotesigner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.remotesigner.ui.AppRoot
import com.remotesigner.ui.theme.RemoteSignerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val psbtBytes = readPsbtFromIntent(intent)

        setContent {
            RemoteSignerTheme {
                AppRoot(intentPsbtBytes = psbtBytes)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-render with new intent data; AppRoot will pick up via LaunchedEffect
    }

    private fun readPsbtFromIntent(intent: Intent): ByteArray? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return try {
            contentResolver.openInputStream(uri)?.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/remotesigner/
git commit -m "feat: Jetpack Compose UI with 4 screens and navigation"
```

---

### Task 11: AndroidManifest, Intent Filters, and FileProvider

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Update AndroidManifest with intent filters and FileProvider**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name="com.chaquo.python.android.PyApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.RemoteSigner">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/Theme.RemoteSigner">

            <!-- Main launcher -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Open .psbt files from file:// URIs -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="file" />
                <data android:pathPattern=".*\\.psbt" />
                <data android:mimeType="*/*" />
            </intent-filter>

            <!-- Open .psbt files from content:// URIs (best-effort) -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>

            <!-- USB device attached -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_device_filter" />
        </activity>

        <!-- FileProvider for sharing signed PSBTs -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 2: Create USB device filter**

```xml
<!-- app/src/main/res/xml/usb_device_filter.xml -->
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Trezor devices (vendor 0x1209) -->
    <usb-device vendor-id="4617" />
</resources>
```

- [ ] **Step 3: Create FileProvider paths**

```xml
<!-- app/src/main/res/xml/file_paths.xml -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="psbt_exports" path="/" />
</paths>
```

- [ ] **Step 4: Add FileProvider dependency to build.gradle.kts**

Add to dependencies block in `app/build.gradle.kts`:
```kotlin
implementation("androidx.core:core-ktx:1.15.0")
```

- [ ] **Step 5: Build and verify**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: AndroidManifest with intent filters, USB device filter, FileProvider"
```

---

### Task 12: End-to-End Device Testing

**This task requires a physical Android device with USB-C OTG and a Trezor Safe 3.**

- [ ] **Step 1: Create a testnet PSBT in Electrum**

On the remote machine:
1. Set up Electrum with a testnet Trezor wallet
2. Create an unsigned transaction
3. Export as `.psbt` file
4. Transfer to Android phone

- [ ] **Step 2: Install and test on device**

```bash
./gradlew installDebug
```

Test the full flow:
1. Open app
2. Tap "Open PSBT File" → select the test PSBT
3. Verify transaction review shows correct outputs, change, fee
4. Connect Trezor via USB-C OTG cable
5. Tap "Sign with Trezor"
6. Confirm on Trezor device
7. Broadcast (testnet)
8. Verify txid on mempool.space/testnet

- [ ] **Step 3: Test error cases**

- Open a non-PSBT file → should show "Invalid PSBT" error
- Tap Sign without Trezor connected → should show "Connect Trezor" message
- Deny USB permission → should show permission error
- Reject transaction on Trezor → should show "cancelled" message

- [ ] **Step 4: Fix any issues found during testing**

Address bugs, API mismatches, or embit/trezorlib quirks discovered during real-device testing. The most likely issues:
- trezorlib Transport API version mismatch (adjust usb_transport.py)
- embit txid byte order (may need reversal for trezorlib)
- Chaquopy type conversion issues (ByteArray ↔ bytes)
- USB endpoint detection on specific devices

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "fix: integration testing fixes from end-to-end device testing"
```
