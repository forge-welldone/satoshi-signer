from pathlib import Path
import json
import shutil
import subprocess
import zipfile


ROOT_DIR = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT_DIR / "nostr_signer/manifest.json"
DIST_DIR = ROOT_DIR / "dist"


def test_package_electrum_plugin_creates_expected_zip() -> None:
    version = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))["version"]
    expected_artifact_path = DIST_DIR / f"nostr_signer-v{version}.zip"

    shutil.rmtree(DIST_DIR, ignore_errors=True)

    completed = subprocess.run(
        ["bash", "scripts/package_electrum_plugin.sh"],
        check=True,
        capture_output=True,
        text=True,
        cwd=ROOT_DIR,
    )

    assert completed.stdout.strip() == str(expected_artifact_path)
    assert expected_artifact_path.is_file()

    with zipfile.ZipFile(expected_artifact_path) as artifact_zip:
        names = artifact_zip.namelist()

    assert "nostr_signer/" in names
    assert "nostr_signer/manifest.json" in names
    assert "nostr_signer/qt.py" in names
    assert all(name.startswith("nostr_signer/") for name in names)
