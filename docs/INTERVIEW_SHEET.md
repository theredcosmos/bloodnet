# BloodNet: interview question sheet

Questions you're likely to get about this project, with short answers in your own words.
The file and class to point at is in brackets where it helps.

---

## 0. The 60-second pitch

> BloodNet connects blood banks and hospitals. Banks record donors and donations, and each donation
> turns into separate units (red cells, platelets, plasma) with their own expiry. Hospitals find nearby banks
> with compatible stock and raise requests. Bank staff allocate units, and the allocation is safe under
> concurrency: I have a test with 50 parallel allocations against real MySQL that proves no unit goes to two
> requests. Banks can transfer stock to each other through a state machine. Scheduled jobs expire old units,
> release stale reservations and suggest rebalancing near-expiry stock.
> It's a Spring Boot modular monolith with seven modules whose boundaries are checked by a test, MySQL with
> Flyway, an Angular 17 frontend, and Docker Compose with nginx in front.

---

## 1. Architecture: "why this instead of that?"

**Q: Why a modular monolith and not microservices?**
One team, one deployable, and the most important operation (allocating units to a request) has to be one ACID
transaction. With microservices I'd need a saga plus compensation just to reserve blood. The modules give me the
same separation in code, and Spring Modulith checks it, so I can still pull a module out later. Phase 9
(notifications over RabbitMQ) shows how.

**Q: What is Spring Modulith, and what does it do here?**
It treats each top-level package as a module. Classes in the module's top package are its API; sub-packages
(`internal`) are private. `ModularityTest` calls `ApplicationModules.verify()`, which fails if a module uses another
module's internal classes or if there's a dependency cycle. I tested it: a class in `donor` that referenced
`inventory.internal.BloodUnit` broke the build with a clear message. It also generates PlantUML module diagrams.

**Q: How did you avoid cycles between modules?**
Inventory reacts to transfer *events*, so inventory depends on transfer and transfer doesn't depend on inventory.
The nearby-banks search lives in inventory, and the frontend calls it directly instead of going through transfer.
The dependency graph: donor→inventory, request→inventory, auth→inventory/request, reporting→inventory/request/transfer,
notification→everything it listens to, inventory→transfer. All of them may use shared.

**Q: Why are events synchronous for stock but async for notifications?**
Moving stock must succeed or fail together with the transfer status, so it's a normal `@EventListener` in the same
transaction. If dispatch doesn't find enough units, the exception rolls back the DISPATCHED status too (there's a
test for this). A notification is a side effect, so it uses `@ApplicationModuleListener`: async, after commit, in its
own transaction. A notification bug can't undo an allocation.

**Q: What happens to an async event if the app crashes?**
The Modulith JDBC event registry writes each event to `EVENT_PUBLICATION` in the same transaction as the business
change. That's basically the transactional outbox pattern. Unfinished publications are resubmitted on restart
(`republish-outstanding-events-on-restart`).

**Q: Why not Kafka or RabbitMQ from day one?**
In-process events cover everything in one JVM with no broker to run. A broker earns its place once something runs
in another process. That's Phase 9, extracting notifications.

---

## 2. Concurrency: the strongest story, know it cold

**Q: Walk me through `allocate()`.** [`AllocationService.allocate`, `UnitSql.lockAvailable`]
1. `SELECT ... FOR UPDATE` on the request row, so two people allocating the *same* request are serialized.
2. Work out compatible donor groups (from the cached compatibility table).
3. For each group (exact group first): `SELECT id FROM blood_unit WHERE ... AND status='AVAILABLE' ORDER BY expires_at LIMIT n FOR UPDATE SKIP LOCKED`.
4. `UPDATE blood_unit SET status='RESERVED', request_id=?, reserved_until=?`.
5. Insert `request_allocation` rows and update the request counters. Commit.

**Q: What does `SKIP LOCKED` do, and why not plain `FOR UPDATE`?**
Plain `FOR UPDATE` waits for rows another transaction has locked. Fifty requests would queue on the same few
units, and once the first commits, the others would find those units already RESERVED. `SKIP LOCKED` just moves
on to the next free unit, so parallel allocations get different units and don't wait for each other.
It's the usual "job queue in a database" pattern.

**Q: Then why lock the request row too?**
`SKIP LOCKED` keeps *different* requests apart. The same request allocated twice at once could over-allocate:
both threads read "needs 5" and each reserves 5. The request row lock makes the second call wait, and then it
sees RESERVED and gets a 409. The test fires 50 threads at one 5-unit request: exactly 5 reserved, 49 clean 409s.

**Q: Why `READ_COMMITTED`?**
MySQL's default is REPEATABLE_READ, where a locking range scan also takes gap/next-key locks. Those block inserts
into the gaps (new donations) and are a common source of deadlocks. Under READ_COMMITTED InnoDB locks only the rows
that match. I explicitly lock every row I change, so I don't need the stronger level.

**Q: What bug did the concurrency test find?**
My first query picked units in one statement with `ORDER BY (blood_group = :preferred) DESC, expires_at`. That
expression can't use the index, so MySQL does a filesort, and InnoDB locks every row it reads *before* sorting and
applying LIMIT. The first transaction locked all 60 matching units and the other 49 skipped them all. Result:
only 3 of 60 units reserved. The fix was one query per blood group, so the index `(bank_id, component_type, status,
blood_group, expires_at)` already gives the rows in expiry order and InnoDB stops after LIMIT rows. After the fix:
60/60 reserved, no duplicates. A single-threaded test would never have shown this.

**Q: How does the test prove no double allocation?**
[`AllocationConcurrencyIT`] Real MySQL 8 in Testcontainers. 80 units, 60 of them compatible, and 50 requests ×
2 units. A `CountDownLatch` releases all 50 threads at the same moment. Assertions:
- no unit has more than one active row in `request_allocation`
- total reserved = min(supply, demand) = 60
- none of the 20 incompatible A+ units were touched
- the sum of `units_allocated` over requests = number of RESERVED rows
- every reserved unit's `request_id` matches its allocation row
- zero exceptions

**Q: Optimistic vs pessimistic locking: where do you use which?**
Pessimistic (row locks) where contention is expected and retries would be wasteful: allocation, transfers,
recording a donation (the donor row is locked so two donations can't both pass the 90-day check). Optimistic
(`@Version`) for rare single-row edits like discarding a unit; a conflict becomes a 409 "please retry".

**Q: What if two app instances run?**
The locks live in MySQL, so it works the same across instances. Scheduled jobs use ShedLock so only one instance
runs each job per tick.

**Q: Can this deadlock?**
Within allocation, units are locked in index order and the request row is always locked first, so lock order is
consistent. If a deadlock does happen, MySQL kills one transaction; Spring turns that into
`CannotAcquireLockException`, which the handler returns as 409 "retry". A retry with backoff would be the next
step if it became frequent.

**Q: What is the reservation timeout for?**
A reservation holds stock for 2 hours. If the hospital never collects it, a job (every minute, ShedLock) finds
expired reservations and releases them, each in its own transaction, re-checking under the row lock in case the
request was issued in the meantime. Units go back to AVAILABLE and the request goes back to PENDING.

---

## 3. Database and JPA

**Q: Why Flyway, and why `ddl-auto=validate`?**
The schema is versioned SQL that gets reviewed like code. Hibernate only checks that the entities match the
tables, so a mismatch fails at startup instead of silently altering production.

**Q: Why native SQL in places instead of JPA?**
JPQL can't express `SKIP LOCKED`, and bulk state changes (expire 500 units) are one `UPDATE` instead of loading
500 entities. Reads and simple edits still use JPA repositories.

**Q: Explain the indexes.**
`idx_unit_alloc (bank_id, component_type, status, blood_group, expires_at)` matches the allocation query exactly:
equality on the first four columns, then rows come out already ordered by `expires_at`. That's what lets
`LIMIT ... SKIP LOCKED` stop early. `idx_unit_expiry (status, expires_at)` serves the expiry job.

**Q: How is the blood compatibility stored?**
In a `blood_compatibility` table generated in `V2__seed_compatibility.sql` from two rules: red cells (donor
antigens ⊆ recipient antigens, Rh- only to Rh-), and plasma (the reverse ABO rule). 126 rows. It's cached in
memory (`CompatibilityMatrix`) because it only changes with a migration. A test checks O- red cells only accepts
O- and AB+ accepts all 8.

**Q: How does the nearby search work?**
`ST_Distance_Sphere(POINT(lng, lat), POINT(:lng, :lat))` gives metres on a sphere. Filter by radius and order by
distance, with a subquery counting available compatible units. For thousands of banks I'd add a bounding-box
prefilter or a SPATIAL index on a POINT SRID 4326 column.

**Q: Why store everything in UTC?**
So "expires at" doesn't move with server time zones. Something I actually hit: the test DB connection lacked
`connectionTimeZone=UTC`, so plain JDBC writes went in as IST and Hibernate's as UTC, 5½ hours apart, and the
reservation-timeout test failed. Fixed in the URL, and the app also sets the JVM default zone to UTC.

**Q: What's `open-in-view: false`?**
It turns off keeping the Hibernate session open during view rendering. All DB access happens in service
transactions, so there are no surprise lazy-load queries from controllers or JSON serialization.

**Q: What is `stock_summary`, and why have it?**
A small read model: counts per bank/group/component, refreshed every minute by a job. The dashboard polls every
60s, so 100 open dashboards read a ~100-row table instead of running 100 GROUP BYs over all units.

---

## 4. Spring Boot specifics

**Q: How does `@Transactional` work, and what's the self-invocation trap?**
Spring wraps the bean in a proxy that opens and commits the transaction around the call. Calling another
`@Transactional` method on `this` skips the proxy. That's why the reservation timeout job calls
`allocation.releaseIfExpired(id)` on another bean, so each request gets its own transaction.

**Q: What does `Propagation.MANDATORY` do?**
It requires an existing transaction and throws if there isn't one. `UnitAllocator` uses it because reserving units
outside the request's locked transaction would be a bug.

**Q: How does the audit log work?**
An `@Audited("ALLOCATE_REQUEST")` annotation plus an `@Around` aspect that writes actor, action, args, outcome
and error to `audit_log`. The aspect order puts it *outside* `@Transactional`, so it writes after the business
transaction has finished: failures are still recorded, and a thread never holds two DB connections at once.

**Q: How are errors returned?**
`GlobalExceptionHandler` maps exceptions to RFC 7807 `ProblemDetail`: 404 not found, 409 conflict / concurrent
update, 422 business rule (e.g. "donor last donated less than 90 days ago"), 400 validation with a per-field
`errors` map, 403 forbidden. Unknown errors become 500 without a stack trace.

**Q: Why 422 vs 409 vs 400?**
400 = the request is malformed (bad phone format). 422 = well-formed but breaks a business rule. 409 = it clashes
with the current state (already issued, row changed by someone else).

**Q: How does validation work?**
Jakarta Bean Validation on request records (`@NotNull`, `@Min`, `@Pattern`, `@Past`) with `@Valid`, plus
`@Validated` on controllers for query params. Business rules are checked in services.

**Q: What is ShedLock?**
`@Scheduled` runs on every instance. ShedLock keeps a row per job in the `shedlock` table; an instance has to grab
that row (with a lock-until time) before running, so only one runs it. `lockAtMostFor` releases it if the
instance dies, and `usingDbTime()` avoids clock-skew problems between servers.

**Q: What are the four jobs?**
Expiry (every 10 min: AVAILABLE units past expiry → EXPIRED, notify the bank), reservation timeout (every minute),
rebalance suggestions (hourly: near-expiry units → nearby banks that are low), and stock summary refresh
(every minute).

**Q: What does Actuator give you?**
`/actuator/health` with liveness/readiness probes (the Docker healthcheck uses readiness), plus info and metrics.
Everything except health/info needs ADMIN.

**Q: How do logs work?**
Every request gets an `X-Request-Id` (from the header or generated) in the MDC, and the JWT filter adds the user.
In dev the log line shows `[requestId] [user]`; in the `prod` profile Spring Boot 3.4's structured logging writes
ECS JSON.

---

## 5. Security

**Q: Walk me through login and an authenticated request.**
`POST /api/auth/login` → BCrypt check → JWT (HS256, 8h) with `sub`, email, role, bankId/hospitalId. Angular stores
it, and `jwtInterceptor` adds `Authorization: Bearer ...`. `JwtAuthFilter` verifies the signature, expiry and
issuer and puts an `AuthUser` in the SecurityContext. No server session.

**Q: Why JWT and not sessions?**
Stateless: any instance can serve any request, with no sticky sessions or session store. The cost is that you
can't revoke a token before it expires; I'd add short-lived access tokens plus refresh tokens, or a token
denylist, if that mattered.

**Q: How do you stop staff touching another bank?**
Two layers. `@PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")` for role checks, then `BankAccess.check(bankId)`
in services, because for things like "allocate request 42" the bank is only known after loading the request.
There are tests: staff of bank 2 allocating a bank 1 request gets 403, and staff1 reading bank 2's inventory gets 403.

**Q: Why is CSRF disabled? Isn't that unsafe?**
CSRF works because browsers send cookies automatically. The token here is in a header that JavaScript adds, so a
forged request from another site can't include it. If I moved the token into a cookie I'd have to turn CSRF
protection back on.

**Q: Where is the token stored, and what's the risk?**
`sessionStorage`. XSS could read it. Angular escapes template output by default, which limits XSS. An httpOnly
cookie is safer against XSS but brings back CSRF. That's a trade-off I can explain either way.

**Q: How do you prevent user enumeration on login?**
Same error for an unknown email and a wrong password, and for an unknown email we still run a BCrypt check
against a dummy hash, so the response time doesn't give it away.

**Q: Where are secrets?**
Env vars: `JWT_SECRET` (startup fails if it's shorter than 256 bits), DB password, demo seed password. `.env` is
git-ignored and `.env.example` is committed.

---

## 6. Domain rules

**Q: What happens when a donation is recorded?**
In one transaction: lock the donor row, check age 18–65 and 90 days since the last donation, save the donation,
update `last_donation_at`, then inventory creates one unit per component with expiry = collected + shelf life
(red cells 42 days, platelets 5, plasma 365, whole blood 35). Shelf life lives on the `ComponentType` enum.

**Q: Why lock the donor row?**
Without it, two staff recording a donation for the same donor at the same moment would both pass the 90-day check.

**Q: Allocation order?**
Exact blood group first (save universal O- for when it's really needed), then first-expired-first-out so units
are used before they go off.

**Q: Transfer state machine?**
REQUESTED → APPROVED → DISPATCHED → RECEIVED, with REJECTED from REQUESTED and CANCELLED from REQUESTED/APPROVED.
The allowed moves are on the `TransferStatus` enum (`canMoveTo`). The sender approves, rejects and dispatches; the
receiver confirms receipt; either side can cancel before dispatch. Each move is logged in `transfer_event`, which
drives the progress steps in the UI.

---

## 7. Testing

**Q: What kinds of tests are there?**
- Unit: `EligibilityPolicyTest`, `BloodRequestTest` (entity state changes), `TransferStatusTest` (parameterized transitions), `JwtServiceTest` (expired/tampered tokens).
- Architecture: `ModularityTest`.
- Integration on real MySQL: `BloodnetFlowIT` (HTTP + real JWT through MockMvc: security, donation → units, request → allocate → issue, timeout, transfer flow and rollback, expiry, dashboard) and `AllocationConcurrencyIT`.
- Angular: Jasmine/Karma tests for the root component and pipes.

**Q: Why Testcontainers and not H2?**
H2 doesn't implement MySQL's `SKIP LOCKED`, gap locking or `ST_Distance_Sphere` the same way. A concurrency test
on H2 would prove nothing.

**Q: Surefire vs failsafe?**
Surefire runs `*Test` in `mvn test` (fast, no Docker). Failsafe runs `*IT` in `mvn verify`. CI runs `verify` and
puts the concurrency table into the GitHub job summary.

**Q: How do you test async notifications?**
After the action, poll the notification table for up to 5 seconds. The listener runs after commit on another
thread, so an immediate assertion would be flaky.

---

## 8. Angular

**Q: Standalone components: why?**
No NgModules, and each component declares its own imports. Routes use `loadComponent`, so each screen is its own
lazy chunk. The login page is about 2.5 KB gzipped, and the initial bundle is around 141 KB transferred.

**Q: Signals or RxJS?**
Signals for component state (`rows`, `loading`, `computed` totals) and RxJS for HTTP and polling. The dashboard
combines the bank selector with `interval(60000)`, and `switchMap` cancels an older request if a newer one starts,
so a slow response can't overwrite fresher data.

**Q: What do the interceptors do?**
`jwtInterceptor` adds the bearer token to `/api` calls. `errorInterceptor` handles all errors in one place: on a
401 it logs out and redirects to login ("bad token redirects to login" was a Phase 2 goal), and otherwise it shows
the server's problem+json `detail` or field errors in a snackbar.

**Q: Guards?**
`authGuard` (logged in?), `roleGuard` (route data `roles`), and `homeGuard` (sends `/` to the role's home page).
They only shape the UX; the server enforces the rules.

**Q: How does the UI know which transfer buttons to show?**
The API returns `allowedNext` for each transfer. The UI filters it by whether my bank is the sender or the
receiver. The backend checks the same rules again.

---

## 9. DevOps

**Q: Explain the Dockerfile.**
Multi-stage: Maven + JDK builds the jar, which is then extracted into Spring Boot layers (dependencies, loader,
application). The runtime stage is a JRE-only Alpine image running as a non-root user. Because the layers are
separate, a code change only rebuilds the small application layer. It also has a healthcheck on the readiness
endpoint and `MaxRAMPercentage` so the JVM respects container memory.

**Q: Why nginx in front?**
It serves the built Angular files (with long cache headers for hashed bundles and no-cache for `index.html`),
falls back to `index.html` for client-side routes, and proxies `/api` to the app. Same origin, so no CORS in
production.

**Q: Compose?**
mysql (with a healthcheck and a named volume) → app (`depends_on: service_healthy`) → web. There's also
`docker-compose.dev.yml` with just MySQL for local work.

**Q: CI?**
GitHub Actions with two jobs. Backend: JDK 17 and `mvn -B verify` (Docker is available on the runner, so the
Testcontainers tests run). Frontend: `npm ci`, a production build, and headless tests. Test reports and the
Modulith docs are uploaded as artifacts.

---

## 10. "What would you change at scale?"
- Allocation is per bank, so it scales with banks. If one bank got hot, I'd shard `blood_unit` by bank or move to a queue per bank.
- Read replicas for reports and the dashboard; keep allocation on the primary.
- Pull notifications out to a service behind RabbitMQ (Phase 9) with the outbox already in place.
- Refresh tokens plus revocation; rate-limit login.
- A spatial index for nearby search.
- WebSockets/SSE for the dashboard if 60-second staleness became a problem.
- Retry with backoff on deadlock/lock timeout exceptions.
- Partition or archive old `audit_log` and `request_allocation` rows.

---

## 11. Quick-fire "what does this do?"

| Thing | One-liner |
|---|---|
| `@Modulithic(sharedModules = "shared")` | Marks the app for Spring Modulith; `shared` is allowed everywhere |
| `@ApplicationModuleListener` | Async + after-commit + new-transaction event listener |
| `@Lock(PESSIMISTIC_WRITE)` | Makes the JPA query `SELECT ... FOR UPDATE` |
| `FOR UPDATE SKIP LOCKED` | Lock rows, skipping any already locked by others |
| `@Version` | Optimistic locking column; stale updates fail |
| `@PreAuthorize` | Method-level authorization in SpEL |
| `@SchedulerLock` | ShedLock: only one instance runs this job |
| `Propagation.MANDATORY` | Must join an existing transaction |
| `Isolation.READ_COMMITTED` | Only committed data is seen; no gap locks in InnoDB |
| `ProblemDetail` | Spring's RFC 7807 error body |
| `ddl-auto: validate` | Hibernate checks entities against the schema, changes nothing |
| `open-in-view: false` | No DB session during view rendering |
| `INSERT IGNORE` + unique key | Idempotent job re-runs |
| `ON DUPLICATE KEY UPDATE` | Upsert (stock summary) |
| `ST_Distance_Sphere` | Great-circle distance in metres |
| `provideHttpClient(withInterceptors(...))` | Functional interceptors in Angular 17 |
| `loadComponent` | Lazy-loads a standalone component per route |
| `switchMap` | Cancels the previous inner request when a new value arrives |
| `takeUntilDestroyed()` | Auto-unsubscribes when the component is destroyed |

---

## 12. Behavioural: "tell me about a bug you fixed"

Use the SKIP LOCKED filesort story (section 2). Structure it as: what I expected → what the test showed (3 of 60)
→ how I found it (thought about how InnoDB locks rows during a filesort) → the fix (one indexed query per group)
→ the result (60/60, test kept as a regression guard).

Backup stories: the Docker 29 / Testcontainers API version mismatch, and the UTC vs IST timestamp bug.
