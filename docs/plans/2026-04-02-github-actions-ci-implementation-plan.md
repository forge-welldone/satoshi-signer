# GitHub Actions CI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add GitHub Actions workflows for fast PR checks and separate Android instrumentation coverage.

**Architecture:** Create one fast workflow for Python and JVM unit tests, plus a second workflow dedicated to emulator-backed Android instrumentation tests. Keep workflow logic simple and independent so failures are easy to diagnose and branch protection can target only the fast checks.

**Tech Stack:** GitHub Actions, Ubuntu runners, setup-python, setup-java, Android SDK/emulator actions, Gradle, pytest.

---

### Task 1: Add fast CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`
- Test: local command verification for `python -m pytest tests/ -v`
- Test: local command verification for `./gradlew testDebugUnitTest`

**Step 1: Write the failing test**

Treat workflow validation as command-level verification for this configuration change. The failing condition is that the repository currently has no GitHub Actions workflow for fast checks.

**Step 2: Run test to verify it fails**

Inspect `.github/workflows/` and confirm no CI workflow exists.
Expected: no `ci.yml` present.

**Step 3: Write minimal implementation**

Create `.github/workflows/ci.yml` with:

- triggers: `pull_request`, `push`
- Python job running `python -m pytest tests/ -v`
- JVM job running `./gradlew testDebugUnitTest`
- caching for pip and Gradle
- JDK 17 setup

**Step 4: Run test to verify it passes**

Run:

- `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt && python -m pytest tests/ -v`
- `./gradlew testDebugUnitTest`

Expected: commands succeed locally, proving the workflow commands match real repository behavior.

**Step 5: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add fast GitHub Actions checks"
```

### Task 2: Add Android instrumentation workflow

**Files:**
- Create: `.github/workflows/android-instrumentation.yml`

**Step 1: Write the failing test**

Treat the missing workflow as the failing condition.

**Step 2: Run test to verify it fails**

Inspect `.github/workflows/` and confirm no instrumentation workflow exists.
Expected: no `android-instrumentation.yml` present.

**Step 3: Write minimal implementation**

Create `.github/workflows/android-instrumentation.yml` with:

- trigger: `workflow_dispatch`
- JDK 17 setup
- Android SDK/emulator setup
- Gradle invocation `./gradlew connectedDebugAndroidTest`

Prefer a well-supported emulator runner action rather than hand-rolled boot polling.

**Step 4: Run test to verify it passes**

Verify workflow structure manually and ensure the Gradle command is the same one documented and used by the repo.

**Step 5: Commit**

```bash
git add .github/workflows/android-instrumentation.yml
git commit -m "ci: add Android instrumentation workflow"
```

### Task 3: Update contributor docs

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Step 1: Write the failing test**

The documentation currently does not describe GitHub Actions CI.

**Step 2: Run test to verify it fails**

Inspect `README.md` and `CLAUDE.md` for missing CI workflow documentation.
Expected: no description of the new workflow split.

**Step 3: Write minimal implementation**

Add short documentation for:

- fast PR workflow
- separate emulator workflow
- what each workflow runs

**Step 4: Run test to verify it passes**

Read the updated sections and confirm they match the actual workflow files.

**Step 5: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: describe GitHub Actions CI workflows"
```

### Task 4: Verify the full change set

**Files:**
- Verify: `.github/workflows/ci.yml`
- Verify: `.github/workflows/android-instrumentation.yml`
- Verify: `README.md`
- Verify: `CLAUDE.md`

**Step 1: Run verification commands**

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt && python -m pytest tests/ -v
./gradlew testDebugUnitTest
```

**Step 2: Review workflow files**

Confirm triggers, action versions, commands, and cache setup match the plan.

**Step 3: Review docs**

Confirm docs describe the new CI split accurately and concisely.

**Step 4: Commit**

```bash
git add .github/workflows README.md CLAUDE.md docs/plans
git commit -m "ci: add GitHub Actions test automation"
```
