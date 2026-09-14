.PHONY: dev dev-infra dev-api dev-stop migrate test test-api test-android build build-api build-android lint lint-api lint-android bootstrap clean

# Cross-platform development bootstrap
dev: dev-infra migrate dev-api

dev-infra:
	@echo "Starting infrastructure services..."
	docker compose up -d postgres redis
	@echo "Waiting for services to be healthy..."
	@sleep 3

dev-api:
	@echo "Starting API server..."
	cd apps/api && npm run start:dev

dev-stop:
	docker compose down

migrate:
	@echo "Running database migrations..."
	cd apps/api && npm run migration:run

migrate-generate:
	cd apps/api && npm run migration:generate

test: test-api test-android

test-api:
	cd apps/api && npm test

test-android:
	cd apps/android && ./gradlew test --no-daemon

build: build-api build-android

build-api:
	cd apps/api && npm run build

build-android:
	cd apps/android && ./gradlew assembleDebug --no-daemon

lint: lint-api lint-android

lint-api:
	cd apps/api && npm run lint

lint-android:
	cd apps/android && ./gradlew lint --no-daemon

bootstrap:
	@chmod +x scripts/bootstrap.sh
	@./scripts/bootstrap.sh

clean:
	docker compose down -v
	rm -rf apps/api/dist apps/api/node_modules
	rm -rf apps/android/**/build apps/android/.gradle

install:
	cd apps/api && npm install
	cd packages/shared-types && npm install && npm run build
	cd packages/api-contracts && npm install && npm run build

docker-up:
	docker compose up -d --build

docker-down:
	docker compose down

health:
	@curl -sf http://localhost:3001/health/live && echo " API live OK" || echo " API live FAILED"
	@curl -sf http://localhost:3001/health/ready && echo " API ready OK" || echo " API ready FAILED"
