# Deploy Viro Reach on Your VPS (Separate From Your Other App)

Run these steps on **your local PC** (where Cursor is installed and SSH to the VPS already works).

Viro Reach does **not** replace or modify your other application. It uses:

| Resource | Viro Reach | Typical other app |
|----------|------------|-------------------|
| Install path | `/opt/viro-reach` | e.g. `/opt/other-app` |
| Compose project | `viro-reach` | separate project name |
| API bind | `127.0.0.1:13001` | often `:3000` or `:8080` |
| TURN port | **3479** | often **3478** |
| Postgres/Redis | internal Docker only | may differ |
| HTTPS | subdomain via **host Caddy** | your main domain |

## Prerequisites

- SSH access to VPS (same as your other app)
- Docker + Docker Compose on VPS
- A **subdomain** DNS A record → VPS IP (e.g. `viro.yourdomain.com`)
- Host Caddy (or nginx) if you use `VIRO_CADDY_MODE=existing`

## 1. Configure

```bash
cd /path/to/viro-reach   # your local clone
cp .env.vps.example .env.vps
```

Edit `.env.vps`:

- `VIRO_VPS_HOST`, `VIRO_VPS_USER`, `VIRO_VPS_PATH=/opt/viro-reach`
- `CADDY_DOMAIN=viro.yourdomain.com`
- `EXTERNAL_IP` = VPS public IPv4
- Generate secrets: `openssl rand -hex 32` for JWT, TURN, ephemeral, postgres

## 2. Deploy from local PC

```bash
chmod +x scripts/vps-deploy.sh scripts/vps-render-config.sh scripts/vps-status.sh
./scripts/vps-deploy.sh
```

This rsyncs the repo to the VPS and starts `postgres`, `redis`, `coturn`, and `api`.

## 3. Point Caddy at Viro Reach (recommended)

If your other app already uses ports 80/443, keep `VIRO_CADDY_MODE=existing` and on the **VPS**:

```bash
sudo mkdir -p /etc/caddy/Caddyfile.d
sudo cp /opt/viro-reach/infra/proxy/Caddyfile.host-snippet /etc/caddy/Caddyfile.d/viro-reach.caddy
# Edit domain in snippet if needed, then:
sudo caddy reload --config /etc/caddy/Caddyfile
```

## 4. Verify

```bash
./scripts/vps-status.sh
curl -s https://viro.yourdomain.com/health/live
curl -s https://viro.yourdomain.com/health/ready
```

TURN (isolated port):

```bash
# On VPS — should see viro-reach-coturn listening on 3479
ss -ulnp | grep 3479
```

## 5. Android hardware test

In `apps/android/app/build.gradle.kts`:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://viro.yourdomain.com\"")
```

Rebuild debug APK and install on two phones.

## Alternative: container Caddy

Set `VIRO_CADDY_MODE=container` and `VIRO_HTTP_PORT=8080` / `VIRO_HTTPS_PORT=8443` if you expose those ports via firewall or a second reverse proxy. Default `existing` avoids conflicting with your other app's Caddy.

## Troubleshooting

| Issue | Fix |
|-------|-----|
| SSH fails | Set `VIRO_SSH_KEY` in `.env.vps` |
| Port 3479 in use | Change `listening-port` in coturn template + `TURN_PUBLIC_URL` |
| API not reachable publicly | Add Caddy snippet; API only listens on localhost |
| Migrations fail | `docker compose -f docker-compose.vps.yml logs api` on VPS |

## Tear down (Viro Reach only)

```bash
ssh user@vps "cd /opt/viro-reach && docker compose -f docker-compose.vps.yml down -v"
# Remove Caddy snippet and reload if added
```

Your other app is untouched.
