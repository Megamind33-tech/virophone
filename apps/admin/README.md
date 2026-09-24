# Viro Reach Admin

The admin console is served by the API at `/api/v1/admin/console`. It is a build-free operator surface with the same authorization boundary as the API:

- live platform overview and 14-day activity pulse
- searchable people directory with account detail and device revocation
- Moment room monitoring and safe host-equivalent ending
- subscription and plan totals
- campaign scheduling, audience controls, promotion editing and ordering
- contextual push notifications with reachable-device preview
- security and admin activity history

The console never displays message bodies, room chat or sealed content. Use an `X-Admin-Key` or an active admin user's bearer token. The page keeps the credential only in session storage.

Backend role architecture supports:

- `USER`
- `SUPPORT`
- `ADMIN`
- `SECURITY_ADMIN`

