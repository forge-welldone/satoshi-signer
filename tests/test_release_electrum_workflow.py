from pathlib import Path
import re

import pytest


WORKFLOW_PATH = Path(".github/workflows/release-electrum.yml")


def assert_release_workflow_contract(workflow: str) -> None:
    assert "electrum-v*" in workflow
    assert "actions/checkout@v4" in workflow
    assert "${GITHUB_REF_NAME#electrum-v}" in workflow
    assert "nostr_signer/manifest.json" in workflow
    assert "scripts/package_electrum_plugin.sh" in workflow
    assert "shasum -a 256" in workflow
    assert "gh release create" in workflow
    assert ".zip" in workflow
    assert ".sha256" in workflow
    assert 'basename "$artifact_path"' in workflow
    assert "CHECKSUM_PATH=%s/%s.sha256" in workflow
    assert re.search(
        r'shasum -a 256 "\$ARTIFACT_NAME" > "\$ARTIFACT_NAME\.sha256"', workflow
    )
    assert 'gh release view "$GITHUB_REF_NAME"' in workflow
    assert 'gh release upload "$GITHUB_REF_NAME"' in workflow
    assert '"$CHECKSUM_PATH"' in workflow
    assert "--clobber" in workflow
    assert "Validate tag format" in workflow
    assert "^electrum-v[0-9]+\\.[0-9]+\\.[0-9]+$" in workflow

    assert (
        "- name: Validate tag format\n"
        "        run: |\n"
        "          if ! printf %s \"$GITHUB_REF_NAME\" | grep -Eq '^electrum-v[0-9]+\\.[0-9]+\\.[0-9]+$'; then\n"
        "            printf 'Tag %s must match electrum-vX.Y.Z\\n' \"$GITHUB_REF_NAME\" >&2\n"
        "            exit 1\n"
        "          fi" in workflow
    )

    manifest_step = re.search(
        r"- name: Validate manifest version matches tag\n"
        r"\s+run: \|\n"
        r'\s+manifest_version="\$\(python3 -c .*nostr_signer/manifest\.json.*\)"\n'
        r'\s+if \[ "\$manifest_version" != "\$VERSION" \]; then\n'
        r"\s+printf 'Tag version %s does not match manifest version %s\\n' \"\$VERSION\" \"\$manifest_version\" >&2\n"
        r"\s+exit 1\n"
        r"\s+fi",
        workflow,
    )
    assert manifest_step

    packaging_step = re.search(
        r"- name: Package Electrum plugin\n"
        r"\s+run: \|\n"
        r'\s+artifact_path="\$\(bash scripts/package_electrum_plugin\.sh\)"\n'
        r'\s+case "\$artifact_path" in\n'
        r"\s+\*\.zip\) ;;&?\n"
        r"\s+\*\)\n"
        r"\s+printf 'Expected zip artifact, got %s\\n' \"\$artifact_path\" >&2\n"
        r"\s+exit 1\n"
        r"\s+;;\n"
        r"\s+esac",
        workflow,
    )
    assert packaging_step

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
        'gh release create "$GITHUB_REF_NAME" --title "Electrum plugin v$VERSION"'
    )
    upload_index = release_body.find('gh release upload "$GITHUB_REF_NAME"')

    assert view_index != -1
    assert create_index != -1
    assert upload_index != -1
    assert view_index < create_index < upload_index
    assert (
        'if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then' in release_body
    )
    assert "fi" in release_body
    assert (
        '"$ARTIFACT_PATH" \\\n            "$CHECKSUM_PATH" \\\n            --clobber'
        in release_body
    )


def test_release_electrum_workflow_matches_contract() -> None:
    assert WORKFLOW_PATH.exists(), "release-electrum workflow is missing"

    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

    assert_release_workflow_contract(workflow)


def test_release_electrum_workflow_rejects_non_rerunnable_release_flow() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        'if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then\n'
        '            gh release create "$GITHUB_REF_NAME" --title "Electrum plugin v$VERSION"\n'
        "          fi\n"
        '          gh release upload "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        "            --clobber",
        'gh release create "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        '            --title "Electrum plugin v$VERSION"',
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_wrong_release_step_order() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        'if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then\n'
        '            gh release create "$GITHUB_REF_NAME" --title "Electrum plugin v$VERSION"\n'
        "          fi\n"
        '          gh release upload "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        "            --clobber",
        'gh release upload "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        "            --clobber\n"
        '          if ! gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then\n'
        '            gh release create "$GITHUB_REF_NAME" --title "Electrum plugin v$VERSION"\n'
        "          fi",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_softened_manifest_validation() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          if [ "$manifest_version" != "$VERSION" ]; then\n'
        '            printf \'Tag version %s does not match manifest version %s\\n\' "$VERSION" "$manifest_version" >&2\n'
        "            exit 1\n"
        "          fi",
        '          if [ "$manifest_version" != "$VERSION" ]; then\n'
        '            printf \'Tag version %s does not match manifest version %s\\n\' "$VERSION" "$manifest_version" >&2\n'
        "          fi",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_non_zip_packaging_output() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          case "$artifact_path" in\n'
        "            *.zip) ;;\n"
        "            *)\n"
        "              printf 'Expected zip artifact, got %s\\n' \"$artifact_path\" >&2\n"
        "              exit 1\n"
        "              ;;\n"
        "          esac",
        '          case "$artifact_path" in\n'
        "            *.tar.gz) ;;\n"
        "            *)\n"
        "              printf 'Expected archive artifact, got %s\\n' \"$artifact_path\" >&2\n"
        "              exit 1\n"
        "              ;;\n"
        "          esac",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_missing_checksum_upload() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          gh release upload "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        '            "$CHECKSUM_PATH" \\\n'
        "            --clobber",
        '          gh release upload "$GITHUB_REF_NAME" \\\n'
        '            "$ARTIFACT_PATH" \\\n'
        "            --clobber",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_softened_checksum_generation() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        '          cd "$ARTIFACT_DIR"\n'
        '          shasum -a 256 "$ARTIFACT_NAME" > "$ARTIFACT_NAME.sha256"',
        '          shasum -a 256 "$ARTIFACT_PATH" > "$CHECKSUM_PATH"',
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)


def test_release_electrum_workflow_rejects_missing_strict_tag_validation() -> None:
    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    broken_workflow = workflow.replace(
        "- name: Validate tag format\n"
        "        run: |\n"
        "          if ! printf %s \"$GITHUB_REF_NAME\" | grep -Eq '^electrum-v[0-9]+\\.[0-9]+\\.[0-9]+$'; then\n"
        "            printf 'Tag %s must match electrum-vX.Y.Z\\n' \"$GITHUB_REF_NAME\" >&2\n"
        "            exit 1\n"
        "          fi\n\n",
        "",
    )

    with pytest.raises(AssertionError):
        assert_release_workflow_contract(broken_workflow)
