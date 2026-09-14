# Phase 1A Baseline

**Date:** 2026-09-14  
**Branch:** `cursor/phase06-docs-c131`  
**Starting commit:** `c4b2b7b`

## Toolchain

| Tool | Version |
|------|---------|
| Node | v22.14.0 |
| npm | 10.9.7 |
| Java | OpenJDK 21.0.10 |
| Gradle | 8.5 |
| Android compileSdk / targetSdk | 34 |
| Android minSdk | 26 |
| Docker | 29.1.3 (daemon present; **container create BLOCKED** — overlay mount error) |
| Docker Compose | 2.40.3 |

## Migrations

| File | Status |
|------|--------|
| `001_initial_schema.sql` | Applied in integration tests |
| `002_offline_trust.sql` | Added in Phase 1A |

## Test baseline (pre–Phase 1A changes)

| Suite | Command | Result |
|-------|---------|--------|
| API unit | `cd apps/api && npm test` | **28/28 PASS** |
| API integration | `npm run test:integration` | **29/29 PASS** |
| API build | `npm run build` | **PASS** |
| Android | `./gradlew assembleDebug test` | **BUILD SUCCESSFUL** |

## Infrastructure status

| Target | Status |
|--------|--------|
| Foreign VPS SSH | **BLOCKED** — no credentials in environment |
| Docker Compose full stack | **BLOCKED** — overlay filesystem error on build VM |
| Host PostgreSQL + Redis | Available (used by integration tests) |
| Physical Android devices | **BLOCKED** — not available in cloud agent VM |

## Phase 0.6 carry-forward

See `docs/PHASE_0_6_REPORT.md` — NO-GO for full product; Phase 1A authorized for verification sprint.
