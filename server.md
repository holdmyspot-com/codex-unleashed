# Server-side changes for feedback, analytics and sign-in

These services support the client changes listed under "Telemetry, feedback and update-check patches" in `plan.md`.
They run on the existing DigitalOcean droplet (1 GB RAM, TOR1). They use sentry.io (project `codex-unleashed`) for
reports and a private Backblaze B2 bucket for logs. Every service is reached over HTTPS only.

## 1. Accounts and sign-in (`/holdmyspot:login`)

- Users sign in by email. Use email verification, a magic link or an OAuth provider; the method is still to be
  decided.
- The server maps each email to an opaque, random account ID such as a UUIDv4. The email is stored only on our server.
- On sign-in, the server issues a short-lived signed access token (for example a 1-hour JWT signed with Ed25519) and a
  refresh token.
  - The access token contains the account ID, the expiry and the token ID.
  - The access token contains no email.
  - Refresh tokens can be revoked.
- Endpoints:
  - `POST /auth/login`
  - `POST /auth/refresh`
  - `POST /auth/logout`
  - `GET /.well-known/jwks.json`
- Account deletion removes:
  - the email mapping;
  - the user's analytics rows;
  - the user's Sentry events, through the Sentry API;
  - the user's B2 objects.

## 2. Feedback service

The client never sends reports to Sentry directly. Only this service holds the Sentry DSN.

1. `POST /feedback` takes the sign-in token, the Sentry `event_id`, the report text and metadata, and the attached
   logs.
2. Reject the request without forwarding it if any of these fails:
   - the token is valid and not expired;
   - the account is in good standing;
   - the per-account and per-IP rate limits allow it (for example 10 reports per account per day);
   - the size limits are met (current client maximums: 126 MiB per report, 64 MiB per attachment);
   - the payload is well formed.
3. Forward the event to Sentry with the server-side DSN.
   - Tag it with `holdmyspot_account_id`, plus `logs: stored | none | upload_failed`.
   - Add a context section holding the log viewer link.
4. Hold the logs on local disk. Write them to B2 at `feedback/YYYY/MM/<event_id>/<name>` only after Sentry accepts the
   event. Invalid reports never reach Sentry or B2.
5. A sweeper runs every few minutes. It removes local or B2 objects whose Sentry event does not exist, and expired
   local files.
6. `GET /logs/<event_id>` lets the team view logs.
   - Staff sign in with a password, or through Cloudflare Access in front of the service.
   - The service redirects to a B2 download URL that expires after a few minutes.
   - The bucket stays private.

Sentry settings:

- an inbound filter that drops events whose release is not one of ours;
- spike protection;
- a per-key rate limit as a backstop;
- an alert on unusual volume.

B2 settings:

- a new private bucket;
- an application key limited to that bucket;
- a lifecycle rule that deletes objects after 30 days, matching Sentry's free-plan retention;
- server-side encryption.

## 3. Analytics receiver

- `POST /analytics/events` takes the client's existing `{"events":[{event_type, event_params}]}` body, with the
  sign-in token.
- It returns 2xx on success. The client does not retry, so the receiver must never block it.
- It rejects invalid tokens with 401. Batch size and rate limits are enforced per account.
- Storage is a Postgres table: `codex_events(id bigserial, received_at timestamptz default now(), account_id uuid,
  event_type text, params jsonb, client_version text)`. It has indexes on `(account_id, received_at)` and
  `(event_type, received_at)`.
- Views:
  - tokens per turn, per session and per user, from the turn events' input, cached input, output, reasoning and total
    token counts, grouped by `session_id` and `thread_id`;
  - tool, approval and error rates.
- Retention: delete raw rows after a period set in the privacy notice (for example 13 months); keep aggregates longer.
- Upstream usage metrics stay disabled, so there is no OpenTelemetry collector and no Prometheus or Grafana.

## 4. Update channel

- The client's update check reads GitHub releases from `holdmyspot-com/codex-unleashed`. No server is needed, but
  release tags must follow the version format the client compares.
- If we later host an install script, serve it over HTTPS and publish its checksum next to the release.

## 5. Optional: plugin-author analytics

- Plugins cannot read the analytics stream from inside the client.
- If we offer analytics to plugin authors, the analytics receiver would forward only plugin-attributed events to an
  endpoint the author registers:
  - events with that author's `plugin_id`;
  - the skill, MCP and plugin-usage events.
- Before any of an author's events are shared, the user must opt in to sharing with that author, and the author must
  agree to data terms.
- Strip the account ID, or replace it with a per-plugin pseudonym.

## 6. Privacy and operations

- Publish a privacy notice. Cover what is collected, why, retention periods, the processors (sentry.io, Backblaze,
  DigitalOcean), opt-out, and the contact for access and deletion requests (PIPEDA).
- Back up Postgres and the account database nightly, to a separate B2 bucket.
- Monitor TLS certificates, disk usage and the services' health.
- Resource estimate for a few thousand users: the feedback service and the analytics receiver each need well under
  100 MB of RAM. Postgres fits within the 1 GB droplet for this volume. This is an estimate; measure it after launch.
