# CodeRank — Design Document

This is the long-form companion to the README. It explains **what** was built, **why** each choice was made over the alternatives that were considered, and the **roadmap to v2** — including two warm-container designs, the problems each raises, and how those problems are addressed.

If you only have five minutes, read **§1 (Architecture)**, **§3 (Design decisions)**, and **§8 (Roadmap to v2)** — that's the heart of the engineering story.

---

## Table of contents

1. [Architecture](#1-architecture)
2. [Request lifecycle](#2-request-lifecycle)
3. [Design decisions](#3-design-decisions)
4. [Security model](#4-security-model)
5. [Concurrency model](#5-concurrency-model)
6. [Database schema rationale](#6-database-schema-rationale)
7. [Error handling and observability](#7-error-handling-and-observability)
8. [Roadmap to v2](#8-roadmap-to-v2)
   - 8.1 [Latency baseline today](#81-latency-baseline-today)
   - 8.2 [Option A — Generic per-language warm pool](#82-option-a--generic-per-language-warm-pool)
   - 8.3 [Option B — Per-user session container](#83-option-b--per-user-session-container)
   - 8.4 [Recommended path](#84-recommended-path)
9. [Known limitations](#9-known-limitations)
10. [Future work beyond v2](#10-future-work-beyond-v2)

---

## 1. Architecture

CodeRank is a single Spring Boot service that fronts a PostgreSQL database and the Docker daemon. Its three responsibilities are cleanly separated by layer:

```
┌──────────┐    HTTP + JWT    ┌──────────────────────────┐
│  Client  │ ────────────────▶│  Spring Boot API server  │
└──────────┘                  │  ┌────────────────────┐  │
                              │  │ Security filters   │  │  JWT, rate limit, CORS
                              │  ├────────────────────┤  │
                              │  │ Controllers + DTOs │  │
                              │  ├────────────────────┤  │
     ┌────────────────────────│  │ Services (sync)    │  │
     │                        │  ├────────────────────┤  │
     │   PostgreSQL 16     ◀──│  │ JPA repositories   │  │
     │  users, languages,     │  └────────────────────┘  │
     │  submissions           │  ┌────────────────────┐  │
     │                        │  │ Async executor pool│  │  4–8 background threads
     │                        │  │ (ThreadPoolTask…)  │  │
     │                        │  └─────────┬──────────┘  │
     │                        └────────────┼─────────────┘
     │                                     │ docker-java
     │                                     ▼
     │                       ┌──────────────────────────┐
     └──────────────────────▶│   Docker engine          │
                             │   (fresh container       │
                             │    per request)          │
                             │  python:3.11-slim        │
                             │  --network none          │
                             │  --memory 256m           │
                             │  --cpus 0.5              │
                             │  --pids-limit 64         │
                             │  10s wall timeout        │
                             └──────────────────────────┘
```

The layered separation (Controller → Service → Repository → Entity) means each layer has exactly one job. You can swap the repository for an in-memory test double without changing the service, change the URL scheme without touching business logic, and run the service in isolation without spinning up a web server.

---

## 2. Request lifecycle

The most important flow — submitting code and reading back the result — works like this:

**Phase 1 — accept (HTTP thread, milliseconds):**

1. `POST /api/v1/submissions` arrives.
2. `JwtAuthFilter` parses the `Authorization: Bearer …` header, verifies the signature, and stuffs the user ID into the `SecurityContext`.
3. `RateLimitFilter` checks the user's token bucket; if empty, returns `429`.
4. Spring validates the request body via `@Valid` annotations on the DTO. Bad input → automatic `400` from the global exception handler.
5. `SubmissionService.create()` opens a transaction, persists a row with `status=QUEUED`, returns the saved entity.
6. The controller — *after* the service transaction has committed — dispatches the work by calling `SubmissionExecutionRunner.runAsync(id)`.
7. Controller returns `202 Accepted` with the new submission's UUID.

**Phase 2 — execute (background thread, seconds):**

8. The `@Async` runner picks up the work on a `taskExecutor` thread.
9. Inside a fresh transaction, it sets `status=RUNNING` and flushes — so any concurrent poll sees the transition.
10. `CodeExecutorService.execute()` does the actual work:
    - Writes source to a temporary host directory
    - `createContainer(image, cmd, hostConfig)` with all the limits
    - `copyArchiveToContainer` copies the source file into `/app` *(no host bind-mount — see §3.2)*
    - `startContainer`
    - `waitContainer.awaitCompletion(10, SECONDS)` — returns `false` on timeout
    - On timeout: `killContainer`; status becomes `TIMEOUT`
    - On completion: collect stdout/stderr via `logContainer`, read exit code via `inspectContainer`; status becomes `SUCCESS` (exit 0) or `ERROR` (non-zero)
    - **`finally`**: `removeContainer(force=true)` and delete temp files — guaranteed cleanup regardless of outcome.
11. The runner updates the submission row with output, exit code, exec time, and `completed_at`.

**Phase 3 — read (HTTP thread, milliseconds):**

12. `GET /api/v1/submissions/{id}` runs `findByIdAndUser_Id(id, currentUserId)` — a user-scoped query that returns 404 even if the requester guesses someone else's UUID.
13. Repository uses `@EntityGraph(attributePaths = "language")` to eagerly join the language so the DTO mapper doesn't hit a `LazyInitializationException` after the transaction closes.
14. Controller returns the current state. Client polls again if not terminal.

---

## 3. Design decisions

Each choice below had a real alternative considered. The rationale matters more than the choice itself.

### 3.1 Per-request, single-use containers

**Decision:** every submission spawns a brand-new container. It is destroyed immediately after execution, regardless of outcome.

**Alternatives considered:** pooled/reused containers (faster), persistent containers per user (much faster), running code directly on the host (zero overhead).

**Why this choice:**
- **Zero state leakage** — user A's code can never see anything user B's code wrote. Files, environment variables, processes — all die with the container.
- **Automatic cleanup** — leaked memory, zombie processes, or filled disk vanish when the container is removed. No bespoke cleanup logic to maintain or get wrong.
- **Crash isolation** — a segfault, fork bomb, or kernel-pressure event affects only that one container, not the host or other users.
- **Simpler security argument** — you don't need to *prove* the container is safe to reuse; you just throw it away. That's a much easier security review.

**Trade-off:** **cold-start cost.** Spinning up a container is ~400 ms – 1 second of overhead per submission. This is acceptable for v1 and addressed in §8.

### 3.2 Source code copied into the container (no host bind-mount)

**Decision:** the user's source file is written to a host temp directory, then copied into the container as a TAR stream using `copyArchiveToContainer`.

**Alternative:** bind-mount the host temp directory as a volume.

**Why copy:**
- Bind-mounts expose a slice of the host filesystem to untrusted code. Even read-only mounts give the container *some* visibility into the host.
- Copying gives the container its own isolated copy with zero host filesystem access.
- Cross-platform: bind-mounts on Windows Docker Desktop go through file-sharing layers that can be slow or break in non-obvious ways; the copy approach uses the Docker API uniformly across OSes.

### 3.3 UUIDs for `users` and `submissions`; `BIGSERIAL` for `languages`

**Decision:** different ID strategies per table, based on whether the ID is exposed externally.

**Why:**
- `submissions.id` is exposed in URLs like `GET /submissions/{id}`. Sequential IDs would let an attacker enumerate `submissions/1, /2, /3, …` and discover that other users exist. UUIDs (generated by Postgres via `gen_random_uuid()`) make enumeration pointless.
- `users.id` is referenced in JWT subjects and submission rows. Same argument.
- `languages.id` is an internal lookup with ~5 rows ever, never exposed in user-facing URLs. Sequential `BIGSERIAL` is simpler and fine.

**Principle:** use UUIDs where IDs are user-facing; sequential IDs are fine where they are not.

### 3.4 Flyway owns the schema; Hibernate validates

**Decision:** all schema changes go through versioned SQL migration files in `db/migration/`. `application.yml` sets `spring.jpa.hibernate.ddl-auto: validate`.

**Alternatives considered:** Hibernate's `update` mode (auto-evolve), `create-drop` (recreate on each boot).

**Why Flyway + `validate`:**
- **Reproducibility:** every environment runs the same migrations in the same order. No "works on my machine" schema surprises.
- **Auditability:** schema history lives in git, reviewable in pull requests.
- **Production safety:** `update` mode can silently make destructive changes; `create-drop` wipes data on every boot. `validate` is read-only — it refuses to start if entities and tables disagree, surfacing drift loudly and early.
- **Bonus safety net:** during development, if you add a JPA field but forget the migration, the app fails to boot with a clear "column not found" error instead of silently corrupting data.

### 3.5 Stateless JWT auth

**Decision:** authentication uses signed JWT tokens. The server keeps no session state.

**Alternative:** server-side sessions (sticky or shared).

**Why JWT:**
- The API is meant to scale horizontally. With sessions, every instance would need a shared session store (Redis) or sticky load balancing. JWTs are self-contained — any instance can verify any token without coordination.
- Mobile and SPA clients are easier — no cookie/CSRF dance.
- Stateless verification is faster (no DB lookup per request).

**Trade-off:** logout/revocation is harder — you can't just "delete the session." v1 accepts this with a short TTL (24 hours). v2 would add a revocation list in Redis for explicit logout.

### 3.6 Async execution with polling (not blocking POST)

**Decision:** `POST /submissions` returns immediately with `202 Accepted` and an ID. Execution runs on a background thread. The client polls `GET /submissions/{id}`.

**Alternative:** synchronous POST that blocks until execution completes.

**Why async:**
- A 10-second blocking POST is bad API design — connections held open under load, no way to cancel, no concurrency benefit, awful UX for slow code.
- The polling contract is **the same contract** whether v1 (in-process executor) or v2 (external queue + worker pool). Migrating internals doesn't break clients.
- The `status` column on `submissions` makes state transitions observable and debuggable from the DB directly.

### 3.7 Per-user rate limit using token bucket

**Decision:** `POST /submissions` is limited to 10 per minute per user via Bucket4j.

**Alternative:** a naive "10 per fixed minute" counter.

**Why token bucket:**
- A fixed counter lets an attacker do 10 at `:59` and 10 at `:00` of the next minute — effectively 20 in two seconds.
- Token bucket refills smoothly: 10 tokens, regenerating at a steady rate. Burst-friendly without being game-able.

**v1 limitation:** the buckets live in an in-memory `ConcurrentHashMap`. Restart wipes them; multiple instances each have their own copy. v2 fix: Bucket4j has a Redis backend that turns the buckets into shared distributed state.

### 3.8 Single thread pool for execution

**Decision:** all async submissions go through a single `ThreadPoolTaskExecutor` (core 4, max 8, queue 50).

**Alternative:** unbounded executor (default `SimpleAsyncTaskExecutor`), or one thread per submission.

**Why a bounded pool:**
- Each background thread holds a DB connection (the runner's `@Transactional` keeps it for the duration of execution — see §5). The Hikari default pool is 10. Max 8 concurrent execs keeps us inside that budget with headroom.
- Each background thread also drives a running container. Without a cap, a flood of submissions could spawn hundreds of containers, exhausting Docker's resources.
- The 50-slot queue absorbs short bursts. Beyond that, the rate limiter (10/min/user) usually kicks in first, so we rarely see queue overflow in practice.

---

## 4. Security model

Defense in depth — multiple layers, each catching a different attack class.

| Layer | What it prevents | Where it lives |
| --- | --- | --- |
| **JWT auth** | Anonymous abuse of any endpoint that isn't `/auth/**` or `GET /languages` | `JwtAuthFilter` + `SecurityConfig` |
| **BCrypt** | Database-dump credential leaks. Deliberately slow (~100 ms/hash) to defeat brute force; per-password salt defeats rainbow tables | `AuthService.register/login` |
| **Per-user rate limit** | Submission flooding from a single authenticated user | `RateLimitFilter` |
| **Input validation** | Oversized payloads (`sourceCode` ≤ 100 KB, `stdin` ≤ 10 KB), blank inputs, unknown languages — rejected with `400` before reaching the executor | `@Valid` on DTOs |
| **`--network none`** | The container has no network interface — code cannot make outbound connections, download payloads, or attack other hosts | Container `HostConfig` |
| **`--memory 256m` + swap disabled** | Memory exhaustion of the host | Container `HostConfig` |
| **`--cpus 0.5`** | CPU starvation; one user's `while True` cannot starve others | Container `HostConfig` |
| **`--pids-limit 64`** | Classic fork bombs (`:(){ :|:& };:`) hit the cap instantly and die | Container `HostConfig` |
| **10-second wall-clock timeout** | Infinite loops; the runaway container is killed via SIGKILL | `awaitCompletion(10, SECONDS) → kill` |
| **`removeContainer` in `finally`** | Container resource leaks; one-shot guarantee preserved even on exceptions | `CodeExecutorService` |
| **UUIDs + user-scoped queries** | Cross-user data access via guessed IDs (`findByIdAndUser_Id`) | `SubmissionRepository`, `SubmissionService` |
| **Generic error messages** | Information leaks via stack traces; production clients see "Internal Server Error" while the server logs the full exception | `GlobalExceptionHandler` |

A note on what is *not* defended against in v1: container escape via kernel exploits. This is mitigated by:
- Keeping the Docker daemon and host kernel patched (operational, not code)
- Running on Docker Desktop's WSL2 backend on the dev machine, which itself adds a VM boundary
- In production, would add: read-only rootfs, drop additional Linux capabilities, run as non-root user inside the container, use gVisor/Kata Containers for kernel-level isolation. All sketched in §10.

---

## 5. Concurrency model

- The Tomcat servlet thread handles HTTP I/O. It persists the submission, dispatches async work, and returns `202` — a few hundred milliseconds at most.
- Code execution runs on `ThreadPoolTaskExecutor` (core 4, max 8, queue 50). Up to 8 containers can be running concurrently.
- When concurrent submissions exceed pool capacity, additional jobs queue (up to 50) and start as workers free up.
- The rate limit (10/min/user) and Tomcat's own connection limit usually bite before queue overflow becomes a concern.

**A trade-off worth being explicit about:** the async runner method is annotated `@Transactional`, so it holds a DB connection for the duration of execution (~1.5–10 seconds). Acceptable in v1 because max concurrent execs (8) is less than the Hikari pool default (10). For production scale, the runner would be split into multiple short transactions:

```
markRunning(id)             ← short tx
   execute(language, code, stdin)   ← long, NO tx
saveResult(id, result)      ← short tx
```

Lazy-loaded fields on `Submission.language` would have to be eagerly fetched (already done via `@EntityGraph` on the read path; for the runner we'd add a similar fetch in the load step).

---

## 6. Database schema rationale

Three tables. The full DDL is in `V1__init_schema.sql`.

```
users               languages              submissions
─────               ─────────              ───────────
id  (uuid, pk)      id  (bigserial, pk)    id  (uuid, pk)
email               name                   user_id  (fk → users)
password_hash       version                language_id (fk → languages)
created_at          docker_image           source_code, stdin
                    source_file            status (varchar)
                    run_command            stdout, stderr
                                           exit_code, exec_time_ms, memory_kb
                                           created_at, completed_at
                                           INDEX (user_id, created_at DESC)
```

A few decisions inside this:

- **`status` as `VARCHAR(20)`, not a Postgres `ENUM` type.** Postgres `ENUM` is a real type; changing it requires `ALTER TYPE`, which can lock the table. `VARCHAR` plus a Java enum (`SubmissionStatus` with `@Enumerated(STRING)`) gives type safety in code with cheap evolution at the DB level.
- **`TIMESTAMPTZ`, not `TIMESTAMP`.** With the JVM forced to UTC at startup, all timestamps are unambiguous, regardless of where the server runs.
- **Composite index `(user_id, created_at DESC)`** matches the "list my submissions newest first" query (`findByUser_IdOrderByCreatedAtDesc`). Without it, Postgres would scan the table per query.
- **`@ManyToOne(fetch = LAZY)`** on `Submission.user` and `Submission.language`. The JPA default for `@ManyToOne` is `EAGER` — which would auto-fetch User and Language on every Submission load, causing silent N+1 queries. Forcing `LAZY` means callers explicitly opt into the join via `@EntityGraph` on the queries that need it.

---

## 7. Error handling and observability

**Errors:** `GlobalExceptionHandler` (`@RestControllerAdvice`) converts every exception into a uniform `ApiError` JSON:

```json
{
  "timestamp": "2026-05-28T19:31:57Z",
  "status": 404,
  "error": "Not Found",
  "message": "Submission not found",
  "path": "/api/v1/submissions/abc-..."
}
```

Three handlers, in precedence order:
- `ResponseStatusException` — what services throw (e.g., `404` for not found). Mapped 1:1.
- `MethodArgumentNotValidException` — `@Valid` failures. Body lists the failed fields.
- `Exception` — catch-all. Logs the stack trace server-side; returns a generic "Something went wrong" to the client. **Never leak stack traces to API consumers.**

**Logging:** SLF4J via Logback (Spring Boot default). The executor logs every container creation, kill, and failure. Hibernate SQL logging is on in development (`show-sql: true, format_sql: true`) — disable in production.

**Observability gaps (v1):** no metrics endpoint, no distributed tracing. v2 would add Micrometer + Prometheus, plus per-submission tracing IDs surfaced in the `ApiError` and logs.

---

## 8. Roadmap to v2

### 8.1 Latency baseline today

v1 spins up a brand-new container for every submission. Measured timings:

| Step | Approx |
|---|---|
| `createContainer` | 50–150 ms |
| Copy source TAR into container | 20–50 ms |
| `startContainer` + Python cold start | 200–500 ms |
| Actual user code | varies |
| `awaitCompletion` + log collection | 50–100 ms |
| `removeContainer` (in `finally`) | 50–100 ms |
| **Total Docker overhead (excluding user code)** | **~400 ms – 1 second** |

Observed end-to-end times for `print("hello")` are 1.0–1.5 seconds. That's our baseline. v2 targets ≤ 300 ms.

The system was deliberately built with this overhead, accepting cold-start cost in exchange for the strongest possible security story (§3.1). v2 reduces the latency *while preserving the same one-shot security guarantee*: containers are still single-use; we only shift *when* the creation cost is paid.

Two designs were considered. Both are documented here.

---

### 8.2 Option A — Generic per-language warm pool

**Idea:** keep a small pool of pre-created containers per language. When a submission arrives, take one from the pool, run user code, destroy it. Asynchronously create a replacement to keep the pool topped up.

```
┌─ WarmContainerPool (per language) ──────────┐
│  idle queue: [c1] [c2] [c3]                 │
│                                             │
│  acquire(lang):                             │
│    1. take c1 off the queue                 │
│    2. schedule async create() to refill     │
│    3. return c1                             │
│                                             │
│  on submission complete:                    │
│    destroy the container (caller's job)     │
│    pool already has fresh ones queued       │
└─────────────────────────────────────────────┘
```

**Properties:**
- Total containers = `POOL_SIZE × languages` — small, bounded by language count, not user count.
- Hot-path latency drops from ~600 ms (create + start) to ~200 ms (start only). Further optimisation (pre-started containers running `sleep infinity` + `docker exec` for code) could push this under 100 ms.
- **One-shot security preserved** — every acquired container is brand-new and destroyed after one use.

**Problems and how this design addresses them:**

| Problem | Mitigation |
|---|---|
| Pool starvation under burst load | Synchronous fallback: if the pool is empty, `acquire()` falls back to creating on demand. The request still succeeds, just slower. Standard backpressure pattern. |
| Pool drift if Docker daemon restarts (queued IDs become stale) | On boot, scan Docker for containers with our `coderank=pool` label and remove anything not in our in-memory queue. Standard reconciliation pattern. |
| Resource leak if the app crashes | All pooled containers have a label; a startup task removes orphans matching that label. A `@PreDestroy` hook drains the queue on graceful shutdown. |
| Async refill falling behind during sustained load | Cap the refill rate; once the pool is empty and on-demand falls back, the rate limit (10/min/user) ensures we don't see runaway pressure. |

**Why this wasn't shipped in v1:** the design is sound, the code is ~150 lines, but verifying its correctness under burst load and shutdown takes meaningful test time. v1 prioritised functional correctness and shipping with documentation. The interface change is a one-liner — `pool.acquire(lang)` in place of `docker.createContainerCmd(...)` — and the rest of `CodeExecutorService.execute()` is unchanged. **This is a v2 swap-in, not a rewrite.**

---

### 8.3 Option B — Per-user session container

**Idea (from mentor discussion):** when a user logs in, immediately create one warm container for them. Their next submission uses that container; on submission, the container runs and is destroyed; a fresh one is created in the background for their next submission. When the user is inactive for 5 minutes — or their token expires — the container is killed.

```
on login   → registry.create(userId, defaultLanguage)
on submit  → take user's container; run; destroy
             async create a fresh one for next submission
on idle/expiry → destroy user's container
```

This is more interesting than Option A: per-user latency drops to near-zero for the *first* submission after login (the container is already warm before they hit Run), not just for steady-state usage. It also gives a meaningful "session" notion that maps cleanly to user activity.

**Problems and how this design addresses each:**

#### Problem 1 — It re-introduces server-side per-user state

JWTs were chosen specifically for statelessness (§3.5). A `UserContainerRegistry` is now stateful in-memory data: `Map<userId, { containerId, languageId, lastActivityAt }>`.

**Mitigation:**
- For v1-scale (single instance), an in-memory `ConcurrentHashMap` is fine. Document explicitly that this is a deliberate trade-off — the API itself remains stateless to clients; only the optimisation layer is stateful.
- For multi-instance, move the registry to Redis with the same `Map` shape. Sticky load balancing optional but not required if container IDs include the host they live on, and the request routes to that host (more complex; defer to v3).
- On app restart: reconcile by scanning Docker for containers labeled `coderank=session` and either adopt or destroy them. Same reconciliation pattern as Option A.

#### Problem 2 — Doesn't scale by user count

One idle container per logged-in user. At 100 concurrent users that's ~2–5 GB of host memory, 100 cgroups, 100 network namespaces. Docker hits limits well before 1000.

**Mitigation:**
- Bound the total: at most N session containers system-wide (`MAX_SESSIONS = 50`). When at cap, additional logins still succeed but skip the warm container — submissions for those users go through the normal on-demand path. They lose the latency benefit; they don't lose functionality.
- LRU eviction: if at cap, the least-recently-active session is destroyed to make room for a new login.
- For the case study scale (single-digit users in a demo), this is theoretical — but it's the right answer when asked "how does this scale?"

#### Problem 3 — Language switch slows down

The warm container is created for the user's *current* language. If they switch from Python to Java between submissions, we have to throw away the warm Python container and synchronously create a Java one. That submission is back to baseline latency.

**Mitigation:**
- Acceptable in v1 — language switches are infrequent.
- A future hybrid keeps a small *global* per-language pool (Option A) underneath the per-user layer. Language switch pulls from the global pool while the user's session refills with a fresh container of the new language. Best of both: per-user warmth + per-language burst absorption.

#### Problem 4 — Cleanup must be bulletproof

The 5-minute idle eviction is critical. Bugs here = leaked containers slowly eating the host.

**Mitigation:**
- `@Scheduled(fixedDelay = 30s)` task scans the registry, kills any session whose `lastActivityAt` is older than 5 minutes.
- `@PreDestroy` hook destroys all session containers on graceful shutdown.
- **Reconciliation loop** (every few minutes) scans Docker for containers labeled `coderank=session` whose IDs aren't in the registry — destroys them. Catches the case where the in-memory state and Docker state drift.
- All Docker calls inside `try/finally`; eviction never leaves a half-deleted state.

#### Problem 5 — Concurrent submissions from one user

If a user POSTs two submissions in quick succession, the first takes the warm container. The second arrives before the async refill completes.

**Mitigation:**
- Synchronous fallback (same as Option A): if a user's slot is empty, `acquire()` creates on demand.
- The rate limit (10/min/user) bounds how badly this can be exploited — at most 10 submissions per minute means even continuous pressure has gaps for refill.

#### Hooks needed

- `AuthService.register()` and `AuthService.login()` → `registry.onLogin(userId, defaultLangId)`.
- `JwtAuthFilter` → `registry.touch(userId)` after a successful token verify.
- `SubmissionService.create()` → `registry.acquireFor(userId, langId)` instead of unconditional create.

**Estimated implementation effort:** 4–6 hours. Not shipped in v1 to protect documentation and demo time. The full design lives here so the path is clear if/when implemented.

---

### 8.4 Recommended path

Build them in this order:

1. **Option A first** (per-language pool) — bounded resources, simple lifecycle, big enough latency win to feel real. Roughly 2–3 hours of work. Foundation for further optimisation.
2. **Option B on top of Option A** — the per-user registry acquires from the per-language pool instead of directly from Docker. Language switches stay fast (pull from pool), per-user warmth still applies for the common case.

That hybrid is what a production code-execution platform actually looks like: bounded global pool + per-user affinity on top.

---

## 9. Known limitations

Honest list of things v1 doesn't do:

- **Python only.** Adding more languages is purely additive — a Flyway migration and a Docker image. The executor is language-agnostic.
- **`stdin` is captured in the DB but not piped into the container.** Wiring stdin requires attaching to the container's stdin stream — straightforward but out of scope for v1.
- **Peak memory usage is not reported (`memoryKb` is always `null`).** The cap is enforced; actual usage reporting requires Docker stats API integration.
- **Rate limit and (future) pool state are in-memory.** Restart wipes them; multi-instance deployments need shared state (Redis).
- **No live updates** — client polls every ~1.2 seconds. Cheap enough for v1; v2 could add Server-Sent Events on the submission detail endpoint for push updates.
- **Test coverage is sparse.** v1 prioritised the hand-built learning path and end-to-end correctness verified manually. A small set of unit tests around `CodeExecutorService` (mocked Docker client) and `SubmissionService` (in-memory DB) is the natural next add.
- **No structured metrics or tracing.** Logs only.
- **No container escape hardening beyond Docker defaults** — see §4. Read-only rootfs, capability dropping, non-root user inside container are all v2 additions.

---

## 10. Future work beyond v2

The natural progression once v2 ships:

- **More languages.** Java, JavaScript (Node), C++, Go, Rust. Each is a Flyway row + a Docker image build.
- **Stdin support.** Attach to container stdin; pipe the submission's `stdin` field. Straightforward.
- **Actual memory reporting** via Docker stats API into `memoryKb`.
- **Redis-backed rate limiter** for multi-instance deployment.
- **Token revocation** via a Redis denylist for proper logout / forced-revoke on password change.
- **Server-Sent Events** for live status push, replacing client polling.
- **Container hardening**: read-only rootfs (with a writable `/tmp` tmpfs), drop additional Linux capabilities (`CAP_NET_RAW`, `CAP_SYS_PTRACE`, etc.), run as non-root user inside the container, optionally swap Docker for gVisor or Kata Containers for kernel-level isolation.
- **Larger output handling**: stream stdout/stderr to S3 once they exceed a threshold (currently they live in TEXT columns).
- **Read replicas** for history queries once the submissions table grows.
- **Observability**: Micrometer → Prometheus, OpenTelemetry tracing across the HTTP → service → executor → Docker path, structured JSON logs with submission IDs.
- **Frontend hardening**: real code editor (Monaco), syntax highlighting per language, diff view between submissions, shareable submission permalinks.
