# GitHub Actions CI Design

## Goal

Add GitHub Actions workflows that give fast pull request feedback for the repo's reliable automated tests, while keeping slower Android emulator coverage separate.

## Approved Direction

Use two workflows:

1. `ci.yml` for fast checks on `pull_request` and `push`
2. `android-instrumentation.yml` for emulator-backed Android tests on `workflow_dispatch`

This keeps required PR checks fast and predictable while still making the full Android instrumentation path available on demand.

## Fast CI Workflow

The fast workflow should run two independent jobs in parallel:

- Python tests via `python -m pytest tests/ -v`
- JVM/Gradle unit tests via `./gradlew testDebugUnitTest`

Expected setup:

- Ubuntu runner
- Python with pip caching
- JDK 17
- Gradle caching
- Android SDK available for Gradle's Android unit-test task

The workflow should avoid emulator startup and avoid release builds, so it stays suitable for every PR.

## Instrumentation Workflow

The Android instrumentation workflow should run separately from normal PR validation:

- manual trigger: `workflow_dispatch`

It should:

- provision JDK 17
- provision Android SDK components required by the emulator
- start an emulator compatible with API 35
- run `./gradlew connectedDebugAndroidTest`

This workflow is intentionally not part of required fast PR gating.

## Failure Model

- Fast workflow failures block normal code merges when branch protection is configured to require those checks.
- Instrumentation failures surface Android integration regressions without slowing or flaking every PR.
- Separate workflows make it easier to distinguish infrastructure issues from code issues.

## Docs Impact

Update `README.md` with the new CI behavior and where tests run.
Update `CLAUDE.md` to document the GitHub Actions workflow split for future contributors.

## Verification Plan

Local verification should confirm the workflow commands are valid for this repo:

- `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements-dev.txt && python -m pytest tests/ -v`
- `./gradlew testDebugUnitTest`

The emulator workflow YAML should be reviewed carefully for action versions, SDK setup, and trigger structure. Full emulator execution may not be practical locally in this session unless the environment already has the necessary Android tooling and images available.
