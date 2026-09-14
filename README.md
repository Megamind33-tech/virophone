# Viro Reach

Viro Reach is an Android-first private calling network. Users authenticate with a verified phone number, discover contacts through hashed phone matching, and place voice calls over the best available route (LAN, Wi-Fi Direct, internet P2P, or TURN relay). The server authorizes every call before media connects; local network discovery uses rotating ephemeral IDs so nearby peers stay anonymous until authorized.

Phase 0 delivers the foundational architecture: modular Android client, NestJS API, PostgreSQL schema, shared TypeScript contracts, transport and voice abstractions, and engineering diagnostic tooling. Production UI, full Linphone integration, and live signaling are planned for later phases.

## Repository structure

```
/workspace
├── apps/
│   ├── android/          # Kotlin multi-module Android app (primary client)
│   ├── api/              # NestJS REST API (auth, discovery, calls, blocks)
│   └── admin/            # Admin UI placeholder (not built in Phase 0)
├── packages/
│   ├── shared-types/     # Domain enums and cross-platform types
│   └── api-contracts/    # v1 endpoint registry and request/response shapes
├── infra/                # Docker, Postgres init, Caddy, coturn, FlexiSIP stubs
├── scripts/              # bootstrap.sh and tooling
├── docs/                 # Architecture and Phase 0 specifications
├── docker-compose.yml    # Local dev stack (postgres, redis, api, caddy, coturn)
└── Makefile              # Common development commands
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for module-level detail.

## Prerequisites

| Requirement | Version / notes |
|-------------|-----------------|
| Node.js | 20.x |
| npm | 9+ |
| Docker & Docker Compose | For Postgres, Redis, and optional full stack |
| Java | 17 (Android builds) |
| Android SDK | API 34; set `ANDROID_HOME` for local Android builds |

## Bootstrap (first-time setup)

```bash
make bootstrap
```

`scripts/bootstrap.sh` performs:

1. Copies `.env.example` → `.env` if missing
2. Installs and builds `packages/shared-types` and `packages/api-contracts`
3. Installs `apps/api` dependencies
4. Starts Postgres and Redis via Docker Compose
5. Runs database migrations (`001_initial_schema.sql`)
6. Builds the API and runs API tests
7. Builds and tests Android when `ANDROID_HOME` is available

## Daily development

Start infrastructure, migrate, and run the API in watch mode:

```bash
make dev
```

Equivalent steps:

```bash
make dev-infra    # docker compose up -d postgres redis
make migrate      # apps/api migration:run
make dev-api      # nest start --watch on port 3001
```

Verify health:

```bash
make health
# or
curl http://localhost:3001/health/live
curl http://localhost:3001/health/ready
```

Other useful targets:

| Command | Description |
|---------|-------------|
| `make test` | API + Android unit tests |
| `make build` | Build API and Android debug APK |
| `make lint` | ESLint (API) and Android lint |
| `make dev-stop` | Stop Docker services |
| `make clean` | Tear down volumes and build artifacts |

## Android app

The Phase 0 entry point is a diagnostic screen, not production UI:

- `apps/android/app/src/main/kotlin/com/viroreach/app/MainActivity.kt`
- `apps/android/app/src/main/kotlin/com/viroreach/app/diagnostic/DiscoveryDiagnosticScreen.kt`

Build and test:

```bash
cd apps/android && ./gradlew assembleDebug test
```

Gradle modules are declared in `apps/android/settings.gradle.kts` (core, feature, transport, voice layers).

## API

- Base URL: `http://localhost:3001`
- Versioned routes: `/api/v1/*`
- Health: `/health/live`, `/health/ready`
- Contracts: `packages/api-contracts/src/index.ts`

## Documentation

| Document | Topic |
|----------|-------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design and monorepo layout |
| [docs/DISCOVERY_PRIVACY.md](docs/DISCOVERY_PRIVACY.md) | Contact and local discovery privacy |
| [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | Auth, tokens, blocking, server authority |
| [docs/CALL_ROUTING.md](docs/CALL_ROUTING.md) | Transports, route engine, voice engine |
| [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md) | PostgreSQL tables and constraints |
| [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md) | v1 REST endpoints |
| [docs/DEPENDENCY_LICENSES.md](docs/DEPENDENCY_LICENSES.md) | Third-party licenses |
| [docs/ANDROID_PERMISSIONS.md](docs/ANDROID_PERMISSIONS.md) | Android manifest permissions |
| [docs/ACCEPTANCE_TESTS.md](docs/ACCEPTANCE_TESTS.md) | Phase 0 acceptance criteria |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Architecture decision records |

## License

See [docs/DEPENDENCY_LICENSES.md](docs/DEPENDENCY_LICENSES.md) for third-party license obligations, including Linphone (AGPL) for planned VoIP integration.
