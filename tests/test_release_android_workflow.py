from pathlib import Path
import re

import pytest


WORKFLOW_PATH = Path(".github/workflows/release-android.yml")


def assert_release_workflow_contract(workflow: str) -> None:
    assert "android-v*" in workflow
    assert "actions/checkout@v4" in workflow
    assert "actions/setup-java@v4" in workflow
    assert "actions/setup-python@v5" in workflow
    assert "android-actions/setup-android@v3" in workflow
    assert "gradle/actions/setup-gradle@v4" in workflow
    assert "${GITHUB_REF_NAME#android-v}" in workflow
    assert "app/build.gradle.kts" in workflow
    assert "./gradlew assembleRelease" in workflow
    assert "app/build/outputs/apk/release/app-release.apk" in workflow
    assert "shasum -a 256" in workflow
    assert "gh release create" in workflow
    assert ".apk" in workflow
    assert ".sha256" in workflow
    assert "RELEASE_KEYSTORE_BASE64" in workflow
    assert "RELEASE_KEYSTORE_PROPERTIES" in workflow
    assert "printf 'Missing required secret: %s\\n'" in workflow
    assert "Validate tag format" in workflow
    assert "^android-v[0-9]+\\.[0-9]+\\.[0-9]+$" in workflow

    assert (
        "- name: Validate tag format\n"
        "        run: |\n"
        "          if ! printf %s \"$GITHUB_REF_NAME\" | grep -Eq '^android-v[0-9]+\\.[0-9]+\\.[0-9]+$'; then\n"
        "            printf 'Tag %s must match android-vX.Y.Z\\n' \"$GITHUB_REF_NAME\" >&2\n"
        "            exit 1\n"
        "          fi" in workflow
    )

    version_step = re.search(
        r"- name: Validate app version matches tag\n"
        r"\s+run: \|\n"
        r'\s+app_version="\$\(python3 -c .*app/build\.gradle\.kts.*versionName.*\)"\n'
        r'\s+if \[ "\$app_version" != "\$VERSION" \]; then\n'
        r"\s+printf 'Tag version %s does not match app version %s\\n' \"\$VERSION\" \"\$app_version\" >&2\n"
        r"\s+exit 1\n"
        r"\s+fi",
        workflow,
    )
    assert version_step

    secret_step = re.search(
        r"- name: Reconstruct signing material\n"
        r"\s+env:\n"
        r"\s+RELEASE_KEYSTORE_BASE64: \$\{\{ secrets\.RELEASE_KEYSTORE_BASE64 \}\}\n"
        r"\s+RELEASE_KEYSTORE_PROPERTIES: \$\{\{ secrets\.RELEASE_KEYSTORE_PROPERTIES \}\}\n"
        r"\s+run: \|\n"
        r"(?P<body>(?:\s+.*\n?)*)",
        workflow,
    )
    assert secret_step

    secret_body = secret_step.group("body")
    assert 'if [ -z "$RELEASE_KEYSTORE_BASE64" ]; then' in secret_body
    assert (
        "printf 'Missing required secret: %s\\n' \"RELEASE_KEYSTORE_BASE64\" >&2"
        in secret_body
    )
    assert 'if [ -z "$RELEASE_KEYSTORE_PROPERTIES" ]; then' in secret_body
    assert (
        "printf 'Missing required secret: %s\\n' \"RELEASE_KEYSTORE_PROPERTIES\" >&2"
        in secret_body
    )
    assert (
        'printf %s "$RELEASE_KEYSTORE_BASE64" | base64 --decode > release.keystore'
        in secret_body
    )
    assert (
        'printf %s "$RELEASE_KEYSTORE_PROPERTIES" > keystore.properties' in secret_body
    )

    artifact_step = re.search(
        r"- name: Capture release artifact paths\n"
        r"\s+run: \|\n"
        r'\s+artifact_path=".*app-release\.apk"\n'
        r'\s+printf \'ARTIFACT_PATH=%s\\n\' "\$artifact_path" >> "\$GITHUB_ENV"\n'
        r'\s+printf \'ARTIFACT_NAME=%s\\n\' "\$\(basename \"\$artifact_path\"\)" >> "\$GITHUB_ENV"\n'
        r'\s+printf \'ARTIFACT_DIR=%s\\n\' "\$\(dirname \"\$artifact_path\"\)" >> "\$GITHUB_ENV"\n'
        r'\s+printf \'CHECKSUM_PATH=%s/%s\.sha256\\n\' "\$\(dirname \"\$artifact_path\"\)" "\$\(basename \"\$artifact_path\"\)" >> "\$GITHUB_ENV"',
        workflow,
    )
    assert artifact_step

    checksum_step = re.search(
        r"- name: Generate sha256\n"
        r"\s+run: \|\n"
        r'\s+cd "\$ARTIFACT_DIR"\n'
        r'\s+shasum -a 256 "\$ARTIFACT_NAME" > "\$ARTIFACT_NAME\.sha256"',
        workflow,
    )
    assert checksum_step

    release_step = re.search(
        r"- name: Create or update GitHub release\n"
        r"\s+env:\n"
        r"\s+GH_TOKEN: \$\{\{ github\.token \}\}\n"
        r"\s+run: \|\n"
        r"(?P<body>(?:\s+.*\n?)*)",
        workflow,
    )
    assert release_step

    release_body = release_step.group("body")
    view_index = release_body.find('gh release view "$GITHUB_REF_NAME"')
    create_index = release_body.find(
        'gh release create "$GITHUB_REF_NAME" --title "Android app v$VERSION"'
    )
    upload_index = release_body.find('gh release upload "$GITHUB_REF_NAME"')

    assert view_index != -1
    assert create_index != -1
    assert upload_index != -1
    assert view_index < create_index < upload_index
    assert (
        'if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then' in release_body
    )
    assert (
        '"$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        "            --clobber" in release_body
    )


def test_release_android_workflow_matches_contract() -> None:
    assert WORKFLOW_PATH.exists(), "release-android workflow is missing"

    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

    assert_release_workflow_contract(workflow)


def test_release_android_workflow_rejects_pathful_checksum_output() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          printf \'ARTIFACT_NAME=%s\\n\' "$(basename "$ARTIFACT_PATH")" >> "$GITHUB_ENV"\n'
        '          printf \'ARTIFACT_DIR=%s\\n\' "$(dirname "$ARTIFACT_PATH")" >> "$GITHUB_ENV"\n'
        '          printf \'CHECKSUM_PATH=%s/%s.sha256\\n\' "$(dirname "$ARTIFACT_PATH")" "$(basename "$ARTIFACT_PATH")" >> "$GITHUB_ENV"',
        '          printf \'CHECKSUM_PATH=%s\\n\' "app/build/outputs/apk/release/app-release.apk.sha256" >> "$GITHUB_ENV"',
    ).replace(
        '          cd "$ARTIFACT_DIR"\n'
        '          shasum -a 256 "$ARTIFACT_NAME" > "$ARTIFACT_NAME.sha256"',
        '          shasum -a 256 "$ARTIFACT_PATH" > "$CHECKSUM_PATH"',
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_android_workflow_rejects_same_step_github_env_reuse() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          artifact_path="app/build/outputs/apk/release/app-release.apk"\n'
        '          printf \'ARTIFACT_PATH=%s\\n\' "$artifact_path" >> "$GITHUB_ENV"\n'
        '          printf \'ARTIFACT_NAME=%s\\n\' "$(basename "$artifact_path")" >> "$GITHUB_ENV"\n'
        '          printf \'ARTIFACT_DIR=%s\\n\' "$(dirname "$artifact_path")" >> "$GITHUB_ENV"\n'
        '          printf \'CHECKSUM_PATH=%s/%s.sha256\\n\' "$(dirname "$artifact_path")" "$(basename "$artifact_path")" >> "$GITHUB_ENV"',
        '          printf \'ARTIFACT_PATH=%s\\n\' "app/build/outputs/apk/release/app-release.apk" >> "$GITHUB_ENV"\n'
        '          printf \'ARTIFACT_NAME=%s\\n\' "$(basename "$ARTIFACT_PATH")" >> "$GITHUB_ENV"\n'
        '          printf \'ARTIFACT_DIR=%s\\n\' "$(dirname "$ARTIFACT_PATH")" >> "$GITHUB_ENV"\n'
        '          printf \'CHECKSUM_PATH=%s/%s.sha256\\n\' "$(dirname "$ARTIFACT_PATH")" "$(basename "$ARTIFACT_PATH")" >> "$GITHUB_ENV"',
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_android_workflow_rejects_missing_strict_tag_validation() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        "- name: Validate tag format\n"
        "        run: |\n"
        "          if ! printf %s \"$GITHUB_REF_NAME\" | grep -Eq '^android-v[0-9]+\\.[0-9]+\\.[0-9]+$'; then\n"
        "            printf 'Tag %s must match android-vX.Y.Z\\n' \"$GITHUB_REF_NAME\" >&2\n"
        "            exit 1\n"
        "          fi\n\n",
        "",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)
