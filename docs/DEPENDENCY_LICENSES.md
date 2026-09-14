# Dependency Licenses (Phase 0)

## Purpose

This document lists major third-party dependencies in the Viro Reach monorepo, their licenses, and commercial implications for product planning. Phase 0 includes stub/integration-point dependencies (Linphone) not yet pulled into builds.

**Disclaimer:** This is an engineering summary, not legal advice. Confirm obligations with counsel before commercial distribution.

---

## License summary

| Component | License | Used in | Commercial note |
|-----------|---------|---------|-----------------|
| **Linphone SDK** (planned) | **AGPL v3** | `apps/android/voice/linphone` | Copyleft applies to combined work; network SaaS trigger; commercial license available from Belledonne Communications |
| **FlexiSIP** (planned) | **AGPL v3** | `infra/flexisip/` | Same as Linphone ecosystem; often licensed together |
| **NestJS** | MIT | `apps/api` | Permissive; attribution in notices |
| **TypeORM** | MIT | `apps/api` | Permissive |
| **PostgreSQL** | PostgreSQL License | Docker infra | Permissive (similar to BSD/MIT) |
| **Redis** | RSALv2 / SSPLv1 (server) | Docker infra | Review SSPL for managed Redis offerings; client library usage differs |
| **coturn** | BSD-style | `infra/coturn/` | Permissive |
| **Caddy** | Apache 2.0 | `infra/proxy/` | Permissive |
| **Kotlin / Android Gradle Plugin** | Apache 2.0 | `apps/android` | Permissive |
| **Jetpack Compose / AndroidX** | Apache 2.0 | `apps/android` | Permissive |
| **Retrofit / OkHttp** | Apache 2.0 | `apps/android/core/network` | Permissive |
| **Room** | Apache 2.0 | `apps/android/core/database` | Permissive |
| **JUnit** | EPL 2.0 | Tests | Test-only; weak copyleft on test artifacts |
| **Jest** | MIT | `apps/api` tests | Permissive |
| **passport-jwt** | MIT | `apps/api` | Permissive |
| **bcrypt** | MIT | `apps/api` | Permissive |
| **uuid** | MIT | `apps/api` | Permissive |
| **ioredis** | MIT | `apps/api` | Permissive |
| **rxjs** | Apache 2.0 | `apps/api` | Permissive |

---

## High-impact: Linphone / AGPL

### Current state

Phase 0 does **not** compile the Linphone SDK. The integration point is commented in:

```
apps/android/voice/linphone/build.gradle.kts
// implementation("org.linphone:linphone-sdk-android:5.2+")
```

`LiblinphoneVoiceEngine.kt` is a stub adapter behind the `VoiceEngine` interface.

### AGPL v3 implications

The GNU Affero General Public License v3 requires, among other obligations:

1. **Source disclosure** — Provide corresponding source to users of the licensed program when distributed.
2. **Network use (Section 13)** — Modified versions used to offer interaction over a network may trigger source-offer requirements for users interacting remotely.
3. **Combined works** — Linking AGPL code with proprietary code in a single application typically requires the combined work to be licensed under AGPL unless a separate commercial license is obtained.

### Mitigation options

| Option | Description |
|--------|-------------|
| Commercial license | Belledonne Communications offers proprietary licenses for Linphone SDK and FlexiSIP |
| Alternative SDK | Replace `:voice:linphone` with a differently licensed implementation of `VoiceEngine` (e.g. WebRTC-only stack with permissive licenses — evaluate patent and feature tradeoffs) |
| Process isolation | Run VoIP in a separate AGPL-licensed service/process with IPC boundary — legal review required; not a automatic safe harbor |
| Open source product | Release client under AGPL-compatible terms |

**Architecture benefit:** The `VoiceEngine` abstraction (`apps/android/voice/api`) exists specifically to swap VoIP backends without rewriting feature modules.

Reference in code:

```kotlin
// LiblinphoneVoiceEngine.kt
// Production integration requires Linphone SDK (AGPL — see DEPENDENCY_LICENSES.md).
```

---

## FlexiSIP (signaling server)

Config stub: `infra/flexisip/flexisip.conf`

FlexiSIP is part of the Linphone/Belledonne ecosystem and is typically **AGPL v3**. Deploying a modified FlexiSIP instance to authorize Viro Reach SIP registrations has the same licensing considerations as the client SDK.

Phase 0 does not run FlexiSIP in the default `make dev` flow.

---

## Backend stack (MIT / permissive)

Primary dependencies from `apps/api/package.json`:

| Package | Version | License |
|---------|---------|---------|
| `@nestjs/common`, `@nestjs/core`, `@nestjs/platform-express` | ^10.3.0 | MIT |
| `@nestjs/jwt`, `@nestjs/passport`, `@nestjs/typeorm`, `@nestjs/throttler` | various | MIT |
| `typeorm` | ^0.3.20 | MIT |
| `pg` | ^8.11.3 | MIT |
| `class-validator`, `class-transformer` | MIT | MIT |
| `passport`, `passport-jwt` | MIT | MIT |
| `rxjs` | ^7.8.1 | Apache 2.0 |

**Commercial use:** Standard MIT/Apache obligations — preserve copyright notices in distributions.

---

## Android stack (Apache 2.0)

From `apps/android/gradle/libs.versions.toml`:

| Library | License |
|---------|---------|
| Android Gradle Plugin 8.2.2 | Apache 2.0 |
| Kotlin 1.9.22 | Apache 2.0 |
| Jetpack Compose BOM 2024.02.00 | Apache 2.0 |
| Room 2.6.1 | Apache 2.0 |
| Retrofit 2.9.0, OkHttp 4.12.0 | Apache 2.0 |
| kotlinx-coroutines | Apache 2.0 |

No copyleft dependencies in the current Android dependency graph.

---

## Infrastructure images

| Image | License | Notes |
|-------|---------|-------|
| `postgres:16-alpine` | PostgreSQL License | Free for commercial use |
| `redis:7-alpine` | Redis source: RSALv2/SSPLv1 | Using official image in dev; review for managed hosting |
| `caddy:2-alpine` | Apache 2.0 | Permissive |
| `coturn/coturn` | BSD-style | Permissive |

---

## Shared packages

| Package | License | Notes |
|---------|---------|-------|
| `@viro-reach/shared-types` | Project license | Internal monorepo package |
| `@viro-reach/api-contracts` | Project license | Internal monorepo package |

Ensure root repository `LICENSE` file reflects project licensing intent (not present in Phase 0 scaffold).

---

## Compliance checklist (pre-release)

- [ ] Resolve Linphone/FlexiSIP licensing strategy (AGPL compliance or commercial license)
- [ ] Generate SBOM for Android and API lockfiles
- [ ] Include `NOTICE` file with Apache/MIT attributions for mobile and server builds
- [ ] Document VoIP codec patent considerations (separate from copyright licensing)
- [ ] Review Redis SSPL if offering hosted infrastructure
- [ ] Verify Google Play policy alignment for `READ_CONTACTS`, location, and nearby Wi-Fi permissions

---

## Related documents

- [ARCHITECTURE.md](ARCHITECTURE.md) — Voice and transport module layout
- [CALL_ROUTING.md](CALL_ROUTING.md) — Linphone integration path
- [DECISIONS.md](DECISIONS.md) — ADR-006 (VoiceEngine abstraction)
