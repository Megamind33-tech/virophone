# Dependency Security (npm audit disposition)

**Scope:** `apps/api/package-lock.json`  
**Audit date:** 2026-09-14  
**Tool:** `npm audit` (npm 10.x)

## Summary

| Severity | Count | Disposition |
|----------|-------|-------------|
| Critical | 1 | Accept (dev transitive); monitor |
| High | 16 | Accept / planned upgrade |
| Moderate | 16 | Accept (mostly dev toolchain) |
| Low | 4 | Accept |
| **Total** | **37** | See per-category rationale below |

Phase 0.6 does **not** run `npm audit fix --force` because available fixes require breaking major-version bumps (`@nestjs/cli@12`, `uuid@14`, `@nestjs/platform-ws@12`).

## Methodology

1. Run `cd apps/api && npm audit` against the locked dependency tree.
2. Classify each finding: **runtime production**, **runtime test**, or **dev-only toolchain**.
3. Disposition: **fix now**, **accept with rationale**, **defer to scheduled upgrade**, or **false positive / not applicable**.

Production API runtime is a compiled NestJS bundle executed via `node dist/main`. Dev-only packages (`@nestjs/cli`, `jest`, `eslint`, `@typescript-eslint/*`) are not deployed to production containers.

## Critical (1)

### `tar` — arbitrary file overwrite / symlink issues

| Field | Value |
|-------|-------|
| Severity | Critical |
| Transitive via | `@nestjs/cli` → `pacote` → `tar` |
| Runtime exposure | **No** — build/CI install only |
| Disposition | **Accept** — dev dependency chain; upgrade `@nestjs/cli` in NestJS 12 migration |

## High (16) — disposition summary

| Package / chain | Exposure | Disposition |
|-----------------|----------|-------------|
| `ws` (via `@nestjs/platform-ws`) | **Runtime** — WebSocket signaling | **Monitor** — direct dep `ws@^8.21.3` added; nested copy may lag. Verify deployed `node_modules/ws` version ≥ 8.21.2 in production image. |
| `bcrypt` → `@mapbox/node-pre-gyp` → `tar` | Runtime (password hashing unused in OTP flow) | **Accept** — bcrypt listed but OTP uses HMAC; low attack surface. Revisit on dependency refresh. |
| `multer` (via `@nestjs/platform-express`) | Runtime — file uploads | **Accept** — API has no multipart upload endpoints in Phase 0.6. |
| `glob`, `minimatch`, `picomatch` | Dev — jest/eslint/cli | **Accept** |
| `lodash` | Transitive dev | **Accept** |
| `@nestjs/cli`, `@typescript-eslint/*` | Dev | **Accept** |

## Moderate (16) — disposition summary

| Package | Exposure | Disposition |
|---------|----------|-------------|
| `uuid` | Runtime (`^9.0.1`) | **Defer** — GHSA buffer bounds check affects v3/v5/v6 API when `buf` provided; Viro uses v4 random UUIDs only. Upgrade to `uuid@11+` in dependency refresh. |
| `@nestjs/*` (common, core, config, throttler, typeorm, websockets, testing) | Mixed | **Accept** — advisories mostly transitive through dev tooling or DoS in non-exposed parsers. |
| `ajv`, `qs`, `file-type` | Transitive | **Accept** — no untrusted schema upload paths |
| `webpack` | Dev — `@nestjs/cli` | **Accept** — SSRF in `buildHttp` not used in API runtime |

## Low (4)

| Package | Disposition |
|---------|-------------|
| `body-parser` | Accept — transitive; JSON body size limits via Nest |
| `external-editor`, `inquirer` | Accept — CLI prompts only |
| `webpack` | Accept — dev |

## Android dependencies

Android Gradle dependencies are **not** covered by `npm audit`. Phase 0.6 adds:

| Package | Version | License | Notes |
|---------|---------|---------|-------|
| `io.getstream:stream-webrtc-android` | 1.1.3 | Apache 2.0 | No OSV scan automated in CI yet |

Recommend adding Gradle dependency scanning (e.g. OWASP Dependency-Check or GitHub Dependabot for Gradle) in a future CI hardening pass.

## Actions taken (Phase 0.6)

- [x] Document all 37 npm audit findings with disposition
- [x] Direct `ws` dependency at `^8.21.3` for signaling gateway
- [ ] Scheduled: NestJS 12 + `@nestjs/cli` upgrade (resolves many transitive highs)
- [ ] Scheduled: `uuid` major upgrade with import path check
- [ ] Add CI `npm audit --audit-level=high` gate with allowlist file (future)

## Production deployment guidance

1. **Production image** — build with `npm ci --omit=dev` so CLI/webpack/eslint trees are excluded.
2. **Verify** — run `npm ls ws` in production image; ensure patched version.
3. **Secrets** — `validateProductionConfig()` fail-closed on dev fallback patterns (see `production-config.ts`).
4. **Do not** run `npm audit fix --force` on release branch without integration test pass.

## Related documents

- [DEPENDENCY_LICENSES.md](DEPENDENCY_LICENSES.md)
- [DEPLOYMENT.md](DEPLOYMENT.md)
- [DECISIONS.md](DECISIONS.md) — ADR-017
