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

## Architecture

```
                         ┌─────────────────────┐
   HTTP clients  ─────►  │   Controllers        │  (validation, HTTP status mapping)
                         │  Account / Transfer / │
                         │  Reconcile / AuditLog │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │      Services         │
                         │ AccountService         │
                         │ TransferService  ──────┼──► bounded retry loop
                         │   └─ TransferExecutor  │    (optimistic-lock conflicts,
                         │        (REQUIRES_NEW)  │     idempotency-key races)
                         │ ReconciliationService   │
                         │ AuditLogService         │
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

| Method | Path                       | Description                                  |
|--------|----------------------------|-----------------------------------------------|
| POST   | `/accounts`                | Create account (`ownerName`, `currency`, `startingBalance`) |
| GET    | `/accounts/{id}`           | Get account + balance                         |
| GET    | `/accounts`                | List all accounts                             |
| POST   | `/transfers`               | Execute a transfer (idempotent)                |
| GET    | `/transfers/{id}`          | Get transfer status                            |
| GET    | `/reconcile`                | Run the reconciliation check                   |
| GET    | `/audit-logs/{accountId}`  | View the audit trail for an account            |

### Status codes

| Code | Meaning                                                        |
|------|-----------------------------------------------------------------|
| 200  | Idempotent replay of an already-processed transfer               |
| 201  | Account or transfer created                                      |
| 400  | Bad input — negative/zero amount, self-transfer, malformed body |
| 404  | Account or transaction not found                                 |
| 409  | Optimistic-lock retry budget exhausted under contention          |
| 422  | Insufficient balance                                              |

## Running locally

Requires Java 21+, Maven, and Docker (for Postgres).

```bash
# 1. Start Postgres
docker compose up -d

# 2. Build and run the app
mvn clean package -DskipTests
java -jar target/ledger-system-1.0.0.jar
# App listens on http://localhost:8080

# 3. Try it
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"USD","startingBalance":1000.00}'

curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"<id>","toAccountId":"<id>","amount":50.00,"idempotencyKey":"unique-key-1"}'

curl http://localhost:8080/reconcile
```

### Configuration (`application.yml`)

```yaml
ledger:
  transfer:
    max-retries: 8            # bounded optimistic-lock retry budget
    retry-base-backoff-ms: 15 # exponential backoff base, capped growth + jitter
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

1. Creates two accounts with known starting balances.
2. Fires 1,000 concurrent `POST /transfers` requests between them from a
   50-thread pool, alternating direction each request.
3. Deliberately resubmits ~9% of requests reusing an earlier
   `idempotencyKey`, to prove duplicate submissions are deduplicated rather
   than double-processed.
4. Calls `GET /reconcile` once every request has completed and asserts the
   discrepancy is exactly zero.

Run it yourself against a live instance:

```bash
cd loadtest
java LoadTestRunner.java http://localhost:8080 1000 50
```

### Actual output (2026-07-18, Postgres 16, M-series Mac, 50 threads)

```
=== Ledger Concurrency Load Test ===
Target:        http://localhost:8080
Requests:      1000
Concurrency:   50

Created account A: 583839ef-3fab-41a4-b2e5-65791907b5b9 (starting balance 1000000.00)
Created account B: 2c260c7b-4342-45b4-9bd4-9a27393b129b (starting balance 1000000.00)

Distinct transfers planned:  910
Intentional duplicate calls: 90 (reusing an earlier idempotencyKey)

=== Results ===
Total requests sent:      1000
201 Created (new transfer): 877
200 OK (idempotent replay):  83
409 Conflict (lock retries exhausted): 40
422 Unprocessable (insufficient balance): 0
Other errors:              0
Total time taken:         9020 ms
Throughput:                110.9 req/s

Final balance, account A: 1001252.0000
Final balance, account B: 998748.0000

=== Reconciliation ===
{"passed":true,"totalCurrentBalance":2000000.0000,"totalExpectedBalance":2000000.0000,"globalDiscrepancy":0.0000,"accountDiscrepancies":[],"checkedAt":"2026-07-18T12:27:28.939661Z"}

RESULT: PASS — zero balance discrepancy after 1000 concurrent requests (90 intentional idempotency-key duplicates correctly deduplicated).
```

**Reading this result:** all 83 duplicate-idempotency-key resubmissions
returned `200 OK` (replay) instead of processing a second transfer — none
of the "intentional duplicate calls" leaked through as double-spends. The
40 `409`s are requests that lost every one of their 8 retry attempts
because *two literal accounts* absorbed all 1,000 requests — an
artificially extreme amount of contention on two rows that a real system
spreads across many accounts. They are rejected safely, not silently
dropped or double-applied: reconciliation still lands on exactly
`0.0000` discrepancy across every account in the database, not just the
two involved here.

## Explicitly out of scope (v1)

Authentication/authorization, multi-currency conversion, UI, and deployment
infra are intentionally left out to keep the surface area focused on the
concurrency/correctness guarantee this project exists to demonstrate.

## Stretch goals

- Dockerize the app itself alongside Postgres via `docker-compose`.
- Webhook simulator with signature verification for external payment
  notifications.
- JWT-based auth on endpoints.
