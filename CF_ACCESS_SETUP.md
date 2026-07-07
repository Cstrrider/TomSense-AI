# Serving TomSense on a domain — Cloudflare Tunnel + Access

The backend identifies users by the `Cf-Access-Authenticated-User-Email`
header, which Cloudflare Access injects at the edge *after* authenticating
the request. Until that's wired up, every request falls back to
`TOMSENSE_OWNER_EMAIL` (localhost mode).

Prereqs: a domain on Cloudflare (free plan is fine) and the stack running
(`docker compose up -d`).

## 1. Tunnel — expose the frontend without opening ports

1. **Zero Trust → Networks → Tunnels → Create a tunnel** (Cloudflared).
2. Run the connector it gives you on the same host, e.g.:
   ```bash
   docker run -d --name cloudflared --restart unless-stopped \
     --network tomsense cloudflare/cloudflared:latest \
     tunnel --no-autoupdate run --token <TUNNEL_TOKEN>
   ```
   (`--network tomsense` lets it reach the frontend by container name.)
3. Add a **Public Hostname**: `tomsense.your-domain.com` →
   `http://tomsense-frontend:80`.

## 2. Access — authenticate at the edge

1. **Zero Trust → Access → Applications → Add an application → Self-hosted**
2. Application configuration:
   - **Name:** `TomSense`
   - **Application domain:** `tomsense.your-domain.com`
   - **Identity providers:** at least one (Google login is fastest; email
     OTP also works)
3. Add a policy:
   - **Name:** `Owner only` · **Action:** `Allow`
   - **Rules → Selector: Emails →** your email — the same one you set as
     `TOMSENSE_OWNER_EMAIL`. Add more emails to invite others; every user
     gets their own chats/memory.
4. Save. Next visit to the domain bounces through `cloudflareaccess.com`.

## 3. Enforce it in the backend

`setup.sh` (domain mode) already set these in `.env`:

```bash
REQUIRE_CF_ACCESS=1
FRONTEND_ORIGIN=https://tomsense.your-domain.com
```

Apply: `docker compose up -d --force-recreate tomsense-backend`. Any
request without the Access header now gets `401`.

Verify: DevTools → Network → any backend call → Request Headers should
include `Cf-Access-Authenticated-User-Email: you@…`.

## Sign-out

The sidebar `⎋` link points at `/cdn-cgi/access/logout` — the standard CF
Access endpoint that clears the session cookie.

## Public share links

Share links (`https://tomsense.your-domain.com/s/<token>`) render a
read-only chat via the backend's unauthenticated `/share/` route — but
Access still gates the whole domain at the edge. To let *anyone* open
them, add a second policy on the same Access app, **above** `Owner only`:

- **Name:** `Public share links` · **Action:** `Bypass`
- **Path matching:** include `/s/*` and `/share/*`

## Android app install note

The PWA manifest is fetched with credentials (`crossorigin=use-credentials`)
so install-to-homescreen works behind Access. The Capacitor APK handles the
Access login inside its WebView (`allowNavigation` covers
`*.cloudflareaccess.com`).
