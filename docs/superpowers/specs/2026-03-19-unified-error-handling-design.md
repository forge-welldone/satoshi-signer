# Unified Error Handling Convention

> Refactoring TODO #8 — Unify error handling across Python-Kotlin boundary.

## Problem

Three inconsistent error patterns across the bridge, plus a bug: `SignerViewModel.broadcast()` doesn't catch `ValueError` from Python input validation, which would crash the app.

## Convention

Two patterns, chosen by function type:

1. **Validation functions raise exceptions** — `parse_psbt` raises `ValueError`. Kotlin consumers catch with try-catch and map to `AppState.Error`.
2. **Operations with multiple outcomes return status dicts** — `sign_psbt` returns `status: complete|partial|cancelled|error`; `broadcast_transaction` returns `status: ok|error`. Kotlin consumers check the typed model's `status` field.

`broadcast_transaction` uses both: raises `ValueError` for input validation (bad hex, unknown network), returns error dict for network failures. Callers must handle both.

## Bug fix

`SignerViewModel.broadcast()` wraps `pythonBridge.broadcast()` in try-catch so `ValueError` from Python shows a user-facing error instead of crashing.

## Changes

| File | Change |
|------|--------|
| `viewmodel/SignerViewModel.kt` | Add try-catch around `pythonBridge.broadcast()` |
| `CLAUDE.md` | Add error convention under Design Decisions |
| `docs/refactoring-todos.md` | Mark #8 as fixed |

## What does NOT change

- `parse_psbt` still raises `ValueError`
- `sign_psbt` still returns status dicts
- `broadcast_transaction` Python code unchanged
- `InboxRepository.handleInboxEvent()` already has silent catch
