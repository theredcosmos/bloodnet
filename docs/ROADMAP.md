# BloodNet roadmap: from portfolio project to production grade

BloodNet already has the hard core in place: a modular monolith with enforced module boundaries,
pessimistic locking with `SKIP LOCKED` for allocation (and a concurrency test that proves it),
an explicit transfer state machine, transactional events, Flyway, ShedLock and Testcontainers.
The items below are ordered by **impact per day of work**. The tags say what each one shows a reviewer.

## Tier 1: small effort, big signal (do these first)

| # | Feature | Why it matters | Shows |
|---|---------|----------------|-------|
| 1 | **Live demo + README overhaul**: link, architecture diagram, 30-second GIF, demo logins, "how allocation stays correct under concurrency" section | Most reviewers never clone the repo, so the README *is* the project | Communication |
| 2 | **Load test with published numbers** (k6 or Gatling): e.g. "500 concurrent allocations, 0 double-reserved units, p95 = X ms" | Turns "I used SKIP LOCKED" into a measured claim | Performance engineering |
| 3 | **Playwright end-to-end tests in CI**: hospital raises request → staff allocates → issues | Proves the whole flow works, not just the units | Testing maturity |
| 4 | **CI/CD**: build images, push to GHCR, auto-deploy `main` to Render after tests pass | "Merging to main ships it" is how real teams work | DevOps |
| 5 | **Audit log viewer** (the `audit_log` table already exists): who did what, filterable | Healthcare systems must answer "who issued this unit?" | Compliance thinking |
| 6 | **Lost / returned transfer step** (a gap today: units can sit `IN_TRANSIT` forever) | Real logistics fail; the state machine should model that | Domain modelling |

## Tier 2: features a real blood bank would ask for

- **Barcode / QR scanning** of unit codes with the phone camera (ISBT 128-style labels) for receive, issue and discard.
- **Cold-chain logging**: storage temperature readings per fridge, alerts on excursions, and auto-quarantine of affected units.
- **Donor engagement**: SMS or email when a donor becomes eligible again, appointment booking, donation camp events.
- **Emergency broadcast**: an urgent request for a rare group (e.g. O-) notifies every bank within N km in real time.
- **Partial issue**: issue what is reserved and keep the remainder open, instead of closing the request.
- **Map view** on the public page (Leaflet + OpenStreetMap; no API key needed).
- **Hindi / Marathi UI** (Angular i18n), because the users are in Pune.
- **Installable PWA** for hospital desks with flaky connectivity.

## Tier 3: platform and scale

- **Real-time updates** with Server-Sent Events or WebSockets for notifications and the dashboard, replacing 60-second polling.
- **Observability**: Micrometer → Prometheus → Grafana dashboards (allocations per minute, wastage, job durations), and OpenTelemetry tracing.
- **Security hardening**: short-lived access token plus httpOnly refresh cookie, account lockout, password reset by email, disable user with token revocation (a token version column), TOTP MFA for admins, OWASP ZAP scan in CI.
- **Redis** for rate limiting and caching shared across instances (the in-memory limiter is per instance).
- **Kubernetes / Helm chart** running 2 replicas, which shows that ShedLock and the row locking really work horizontally.
- **Demand forecasting**: predict next week's demand per group and component from request history (start simple, e.g. moving average or Prophet) and flag likely shortages.
- **Multi-region tenancy**: each state health department sees only its own banks.

## Already fixed in this round (for the changelog)

Security: demo users were seeded by default in compose; the prod profile fell back to the dev JWT secret;
the public rate limit could be skipped via a percent-encoded path; login and admin sign-up had no rate limit.

Correctness: units that expire during their 2-hour reservation could be reserved and then block issue;
deadlocks returned 500; a null or contradictory donation component list returned 500 or double-counted stock;
passwords over 72 bytes returned 500; stale rebalance suggestions; the "expiring within" filter included
expired units; staff at the receiving bank could not dismiss suggestions shown to them.

UI: race conditions on inventory, public search, hospital and transfer pages; paginators not resetting;
the user form missing bank/hospital validation; the public page bouncing to login on a stale token;
UTC dates in reports; CSV escaping; duplicate submissions; admin transfer tabs showing identical lists.
