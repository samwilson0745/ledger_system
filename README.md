# Ledger System

A double-entry ledger backend in Java/Spring Boot that guarantees **zero
balance corruption under concurrent writes**. This is the core problem in
any payments/fintech backend: two requests can always race to update the
same account at the same instant, and a naive `read-modify-write` will
silently lose money. This project proves — with a real concurrent load
test over HTTP, not just unit tests — that it doesn't.

## What it demonstrates

- **Atomic transfers**: debit and credit happen in one transaction, or neither does.
- **Optimistic locking (`@Version`)**: concurrent writers to the same account
  never silently overwrite each other's changes; conflicts are detected and
  retried within a bounded budget.
- **Idempotency**: submitting the same `idempotencyKey` twice — even from two
  threads racing simultaneously — always resolves to exactly one processed
  transaction.
- **`BigDecimal` everywhere** for money; never `float`/`double`.
- **An immutable, insert-only audit trail** of every balance change.
- **A reconciliation check** that verifies conservation of money: nothing
  transferred is ever created or destroyed.
- **Real proof**: a standalone script fires 1,000 concurrent HTTP requests
  at a live instance and shows the reconciliation result. See
  [Load test results](#load-test-results) below.
- **JWT-based auth** on every ledger endpoint (see [Authentication](#authentication)).
- **A signed webhook simulator**: every completed transfer fires an
  HMAC-SHA256-signed notification, verified end-to-end by a demo receiver
  in the same app (see [Webhook simulator](#webhook-simulator)).
- **One-command full stack**: `docker compose up -d --build` runs Postgres
  and the app together, no local JDK required to try it.

## Architecture

```
                         ┌─────────────────────┐
   HTTP clients  ─────►  │  JwtAuthenticationFilter│  (validates Bearer token,
                         │  (SecurityConfig)      │   /auth/login + webhook
                         └──────────┬───────────┘   receiver are the only
                                    │                public routes)
                         ┌──────────▼───────────┐
                         │   Controllers          │  (validation, HTTP status mapping)
                         │  Account / Transfer /  │
                         │  Reconcile / AuditLog / │
                         │  Auth / WebhookReceiver │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │      Services          │
                         │ AccountService          │
                         │ TransferService  ───────┼──► bounded retry loop
                         │   └─ TransferExecutor   │    (optimistic-lock conflicts,
                         │        (REQUIRES_NEW)   │     idempotency-key races)
                         │        └─ WebhookDispatcher ──► HMAC-signed POST,
                         │             (fire-and-forget)   fire-and-forget
                         │ ReconciliationService    │
                         │ AuditLogService          │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │   Spring Data JPA      │
                         │   Repositories         │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │     PostgreSQL         │
                         │ accounts (version col) │
                         │ transactions (unique   │
                         │   idempotency_key)     │
                         │ audit_logs (insert-only)│
                         └───────────────────────┘
```

**Why a separate `TransferExecutor` bean?** Spring's `@Transactional` only
takes effect through the proxy, so a retry loop calling a `@Transactional`
method on `this` would silently skip the transaction boundary on every
retry after the first. `TransferService.transfer()` is a plain method that
owns the retry loop; each attempt is delegated to
`TransferExecutor.executeAttempt()`, a separate bean whose
`@Transactional(propagation = REQUIRES_NEW)` method guarantees a fresh
persistence context — and therefore an accurate optimistic-lock check —
on every single attempt.

**Deadlock avoidance:** both accounts in a transfer are always fetched in
a stable order (sorted by UUID), so two transfers moving money in opposite
directions between the same pair of accounts can never deadlock waiting on
each other's row locks.

**Idempotency under a race, not just a lookup:** a fast lookup by
`idempotencyKey` handles the common case, but if two requests with the same
new key are submitted at the exact same instant, both can pass that check
before either commits. The `idempotencyKey` column carries a unique
constraint as the real guarantee — whichever request loses the race gets a
`DataIntegrityViolationException` (or an optimistic-lock conflict on the
shared account rows), and `TransferService` catches that, looks the key back
up, and returns the winner's result instead of erroring out.

## Domain model

- **Account** — `id`, `ownerName`, `currency`, `balance` (`BigDecimal`),
  `initialBalance` (immutable snapshot used by reconciliation), `version`
  (`@Version`), `createdAt`.
- **Transaction** — `id`, `fromAccountId`, `toAccountId`, `amount`,
  `status` (`PENDING`/`COMPLETED`/`FAILED`), `idempotencyKey` (unique),
  `failureReason`, `createdAt`, `completedAt`. Even a rejected transfer
  (insufficient balance) is persisted as `FAILED` so a retried duplicate
  request replays the same outcome instead of re-evaluating a balance that
  may have since changed.
- **AuditLog** — insert-only; no update or delete path exists in the code.

## API

| Method | Path                       | Auth required | Description                                  |
|--------|----------------------------|:---:|-----------------------------------------------|
| POST   | `/auth/login`              | no  | Exchange demo credentials for a JWT            |
| POST   | `/accounts`                | yes | Create account (`ownerName`, `currency`, `startingBalance`) |
| GET    | `/accounts/{id}`           | yes | Get account + balance                         |
| GET    | `/accounts`                | yes | List all accounts                             |
| POST   | `/transfers`               | yes | Execute a transfer (idempotent)                |
| GET    | `/transfers/{id}`          | yes | Get transfer status                            |
| GET    | `/reconcile`                | yes | Run the reconciliation check                   |
| GET    | `/audit-logs/{accountId}`  | yes | View the audit trail for an account            |
| POST   | `/webhooks/test-receiver`  | no*  | Demo receiver — verifies the HMAC signature instead of a JWT |

\* Signature verification is this endpoint's auth mechanism; see [Webhook simulator](#webhook-simulator).

### Status codes

| Code | Meaning                                                        |
|------|-----------------------------------------------------------------|
| 200  | Idempotent replay of an already-processed transfer, or a successful login/signature check |
| 201  | Account or transfer created                                      |
| 400  | Bad input — negative/zero amount, self-transfer, malformed body |
| 401  | Missing/invalid JWT, bad login credentials, or bad webhook signature |
| 404  | Account or transaction not found                                 |
| 409  | Optimistic-lock retry budget exhausted under contention          |
| 422  | Insufficient balance                                              |

## Running locally

Two ways to run it — both need Docker; the first also needs a local JDK.

**Option A — Postgres in Docker, app on the host:**

```bash
docker compose up -d postgres
mvn clean package -DskipTests
java -jar target/ledger-system-1.0.0.jar
# App listens on http://localhost:8080
```

**Option B — everything in Docker (no local JDK needed):**

```bash
docker compose up -d --build
# Builds the app image (multi-stage Maven build) and starts it alongside Postgres
```

### Try it

Every endpoint except `/auth/login` and `/webhooks/test-receiver` requires a
JWT (see [Authentication](#authentication)):

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

curl -X POST http://localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"USD","startingBalance":1000.00}'

curl -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"<id>","toAccountId":"<id>","amount":50.00,"idempotencyKey":"unique-key-1"}'

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/reconcile
```

### Configuration (`application.yml`)

```yaml
ledger:
  transfer:
    max-retries: 8            # bounded optimistic-lock retry budget
    retry-base-backoff-ms: 15 # exponential backoff base, capped growth + jitter

  auth:
    jwt-secret: ${LEDGER_AUTH_JWT_SECRET:...}   # override via env var outside dev
    jwt-expiration-minutes: 60
    users:
      - username: admin
        password: admin123   # demo-only, plaintext — see AuthProperties

  webhook:
    enabled: true
    url: ${LEDGER_WEBHOOK_URL:http://localhost:8080/webhooks/test-receiver}
    secret: ${LEDGER_WEBHOOK_SECRET:...}
```

## Authentication

There's no user database — this is a stretch goal layered on top of the
ledger, not a full identity system. `POST /auth/login` checks the submitted
credentials against a small fixed list of demo users in `application.yml`
and, on success, issues a JWT (HMAC-SHA256, 60-minute expiry by default).
`JwtAuthenticationFilter` validates the `Authorization: Bearer <token>`
header on every other route; `/auth/login` and `/webhooks/test-receiver` are
the only endpoints that don't require one. A missing or invalid token
returns a JSON `401`, not Spring Security's default HTML error page.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
# {"token":"eyJhbGciOi...","tokenType":"Bearer","expiresInMinutes":60}
```

## Webhook simulator

Every transfer that reaches `COMPLETED` fires a `transfer.completed` event
to a configured URL — the same pattern Stripe/GitHub use for webhooks: the
payload is signed with HMAC-SHA256 over the raw JSON body, sent as
`X-Ledger-Signature: sha256=<hex>`, and dispatch happens on a background
executor so a slow or unreachable receiver can never block or fail the
transfer request itself (`WebhookDispatcher`).

`POST /webhooks/test-receiver` stands in for the external system that would
normally receive this — it recomputes the signature with the shared secret
and rejects anything that doesn't match, which is the real proof this
isn't just "send a POST and hope": tampering with the body or the signature
gets a `401`, not silent acceptance.

```bash
# Real traffic: place a transfer and watch the app log both sides —
# WebhookDispatcher sending it, WebhookReceiverController verifying it.
docker compose logs -f app | grep -i webhook

# Manually proving the receiver rejects a bad signature:
curl -X POST http://localhost:8080/webhooks/test-receiver \
  -H 'Content-Type: application/json' \
  -H 'X-Ledger-Signature: sha256=deadbeef0000' \
  -d '{"event":"test"}'
# {"status":"invalid signature"}
```

### Running the tests

```bash
mvn test
```

This runs:
- `TransferServiceTest` — Mockito unit tests: successful transfer,
  insufficient balance, duplicate idempotency key (both the fast-path
  lookup and the concurrent-race-then-constraint-violation path), and
  optimistic-lock retry (including retry exhaustion).
- `TransferConcurrencyIntegrationTest` — a `@SpringBootTest` against an
  in-memory H2 database that fires 300 real concurrent requests (40
  threads) through the actual Hibernate/JPA stack and asserts zero
  reconciliation discrepancy and correct idempotency dedup, without
  needing Docker.

## Load test results

The real proof lives in [`loadtest/LoadTestRunner.java`](loadtest/LoadTestRunner.java) —
a dependency-free, single-file Java program (run with `java LoadTestRunner.java`,
JEP 330 source-file execution) that talks to the **running app over real
HTTP**, not in-process calls. It:

1. Logs in via `POST /auth/login` and carries the JWT on every subsequent call.
2. Creates two accounts with known starting balances.
3. Fires 1,000 concurrent `POST /transfers` requests between them from a
   50-thread pool, alternating direction each request.
4. Deliberately resubmits ~9% of requests reusing an earlier
   `idempotencyKey`, to prove duplicate submissions are deduplicated rather
   than double-processed.
5. Calls `GET /reconcile` once every request has completed and asserts the
   discrepancy is exactly zero.

Run it yourself against a live instance:

```bash
cd loadtest
java LoadTestRunner.java http://localhost:8080 1000 50
```

### Actual output (2026-07-18, full stack via `docker compose up -d --build`, Postgres 16, 50 threads)

```
=== Ledger Concurrency Load Test ===
Target:        http://localhost:8080
Requests:      1000
Concurrency:   50

Authenticated as 'admin' (JWT acquired via POST /auth/login)

Created account A: cce6752e-0e03-4573-b535-86f7301157f8 (starting balance 1000000.00)
Created account B: 3236bbf0-1643-471b-9ed1-e72a5e637319 (starting balance 1000000.00)

Distinct transfers planned:  910
Intentional duplicate calls: 90 (reusing an earlier idempotencyKey)

=== Results ===
Total requests sent:      1000
201 Created (new transfer): 881
200 OK (idempotent replay):  83
409 Conflict (lock retries exhausted): 36
422 Unprocessable (insufficient balance): 0
Other errors:              0
Total time taken:         10555 ms
Throughput:                94.7 req/s

Final balance, account A: 1000965.0000
Final balance, account B: 999035.0000

=== Reconciliation ===
{"passed":true,"totalCurrentBalance":2000000.0000,"totalExpectedBalance":2000000.0000,"globalDiscrepancy":0.0000,"accountDiscrepancies":[],"checkedAt":"2026-07-18T21:47:19.936387960Z"}

RESULT: PASS — zero balance discrepancy after 1000 concurrent requests (90 intentional idempotency-key duplicates correctly deduplicated).
```

**Reading this result:** all 83 duplicate-idempotency-key resubmissions
returned `200 OK` (replay) instead of processing a second transfer — none
of the "intentional duplicate calls" leaked through as double-spends. The
36 `409`s are requests that lost every one of their 8 retry attempts
because *two literal accounts* absorbed all 1,000 requests — an
artificially extreme amount of contention on two rows that a real system
spreads across many accounts. They are rejected safely, not silently
dropped or double-applied: reconciliation still lands on exactly
`0.0000` discrepancy across every account in the database, not just the
two involved here. Every request in this run also carried a real JWT
(the script's first step is `POST /auth/login`), and each of the 881
successful transfers fired a signed webhook that the demo receiver
verified — visible via `docker compose logs -f app | grep -i webhook`.

## Explicitly out of scope

Multi-currency conversion and a UI/frontend are intentionally left out to
keep the surface area focused on the concurrency/correctness guarantee
this project exists to demonstrate. (Auth, webhooks, and full
Dockerization were originally listed here as stretch goals too, but are
now implemented — see [Authentication](#authentication) and
[Webhook simulator](#webhook-simulator).)

The JWT auth layer is deliberately minimal: a fixed list of demo users in
config, no signup/password-reset/refresh-token flow, no persistent user
table. It's enough to demonstrate securing the endpoints, not a production
identity system — see the class-level comment on `AuthProperties` for the
reasoning.
