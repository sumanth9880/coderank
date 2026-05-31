# CodeRank

> An online code execution platform. Submit code over a REST API; it runs inside an isolated Docker container with strict resource and time limits, and the result is returned to you.

Built as the backend case study for the Airtribe Backend Engineering Launchpad. The project exercises containerization, security, concurrency, and REST API design end to end.

---

## Table of contents

- [What it does](#what-it-does)
- [Features mapped to case study](#features-mapped-to-case-study)
- [Architecture at a glance](#architecture-at-a-glance)
- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Setup and run](#setup-and-run)
- [Quick smoke test](#quick-smoke-test)
- [Frontend](#frontend-optional)
- [Project structure](#project-structure)
- [Further reading](#further-reading)

---

## What it does

A logged-in user submits a snippet of Python source code. The API accepts it, queues it, runs it in a fresh sandboxed container, and stores the result. The user then polls a second endpoint to fetch status and output as it transitions `QUEUED → RUNNING → SUCCESS | ERROR | TIMEOUT`.

The platform is designed to execute code it does not trust. Every submission runs in its own one-shot container with **no network access**, **capped memory and CPU**, a **process-count limit**, and a **hard wall-clock timeout**. The container is destroyed immediately after use.

---

## Features mapped to case study

| Case study requirement | Implementation |
| --- | --- |
| 1. Language support | `languages` table maps each `(name, version)` to a Docker image and run command. Adding a language is a single Flyway migration + a Docker image. v1 ships with **Python 3.11**. |
| 2. Code execution API | Versioned REST API under `/api/v1`. Submit returns `202 Accepted` immediately; the client polls for results. |
| 3. Security against malicious code | One-shot Docker containers with `--network none`, memory + CPU + pid caps, source copied in via TAR stream (no host bind mounts). JWT auth, BCrypt passwords, per-user rate limit. |
| 4. Concurrency | Spring `ThreadPoolTaskExecutor` (core 4, max 8, queue 50) processes submissions in parallel. The HTTP thread returns instantly; execution runs on background threads. |
| 5. Timeout and error handling | 10-second wall-clock timeout in the executor. Centralized `@RestControllerAdvice` returns a consistent JSON error envelope (`ApiError`). |
| 6. Resource management | Each container is constrained to 256 MB memory (swap disabled), 0.5 CPU, 64 processes. Cleanup is guaranteed via `try/finally`. |
| Bonus: authentication & authorization | JWT (HS384) issued on register/login; stateless verification on every request. Submissions are user-scoped — a user cannot read another user's results even if they guess the UUID. |
| Bonus: rate limiting | Per-user token bucket (10 submissions/minute, burst-friendly) via Bucket4j. |

---

## Architecture at a glance

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
                             │   (fresh per request)    │
                             │  ┌────────────────────┐  │
                             │  │ python:3.11-slim   │  │
                             │  │  --network none    │  │
                             │  │  --memory 256m     │  │
                             │  │  --cpus 0.5        │  │
                             │  │  --pids-limit 64   │  │
                             │  │  10s wall timeout  │  │
                             │  └────────────────────┘  │
                             └──────────────────────────┘
```

See **[DESIGN.md](./DESIGN.md)** for the request lifecycle, decisions, and the v2 roadmap.

---

## Tech stack

| Layer | Choice | Why (one-liner) |
| --- | --- | --- |
| Language | **Java 21 (LTS)** | Stable LTS; strong ecosystem. |
| Framework | **Spring Boot 3.5** | REST, Security, JPA, validation, autoconfiguration. |
| Database | **PostgreSQL 16** | ACID for submission state; relational fits the data. |
| Schema | **Flyway** | Versioned migrations owned by SQL, not Hibernate auto-DDL. |
| Container runtime | **Docker** via **docker-java** (`zerodep` transport) | Standard isolation primitive; reliable cross-platform (Windows named pipe). |
| Auth | **JWT** (jjwt 0.12, HS384) | Stateless; scales horizontally without a session store. |
| Rate limit | **Bucket4j** | Token bucket with burst tolerance; in-memory v1, Redis-ready for v2. |
| Build | **Maven** | First-class Spring Initializr support; readable POM. |
| Frontend | Single-file React via CDN | Demonstrates the API without a separate build pipeline. |

---

## Prerequisites

- **JDK 21+** (Java 22 also works; project targets 21)
- **Maven 3.9+**
- **Docker Desktop** (Windows/Mac) or Docker Engine (Linux), running
- The Python image cached locally: `docker pull python:3.11-slim`

---

## Setup and run

### 1. Start PostgreSQL in Docker

Port 5433 is used on the host to avoid colliding with any native Postgres on 5432:

```bash
docker run -d --name coderank-pg \
  -e POSTGRES_DB=coderank \
  -e POSTGRES_USER=coderank \
  -e POSTGRES_PASSWORD=coderank \
  -p 5433:5432 \
  postgres:16
```

To stop / start later: `docker stop coderank-pg` / `docker start coderank-pg`. To reset entirely: `docker rm -f coderank-pg`, then re-run the command above.

### 2. (Optional) Override the JWT secret

The default in `application.yml` works locally. For any other environment:

```bash
# Linux/Mac
export CODERANK_JWT_SECRET="a-real-secret-at-least-32-bytes-long-please"

# Windows PowerShell
$env:CODERANK_JWT_SECRET="a-real-secret-at-least-32-bytes-long-please"
```

### 3. Pull the Python execution image

```bash
docker pull python:3.11-slim
```

### 4. Run the application

```bash
mvn spring-boot:run
```

On first boot, Flyway applies `V1__init_schema.sql` (creates `users`, `languages`, `submissions`) and `V2__seed_languages.sql` (inserts Python 3.11). The API becomes available at **http://localhost:8080**. Subsequent boots reuse the schema.

### Boot signals to look for

- `Schema "public" is up to date.` — Flyway happy.
- `HikariPool-1 - Start completed.` — DB connection pool ready.
- `Tomcat started on port 8080` — HTTP listening.
- `Started CoderankApplication in X seconds` — boot complete.

---

## Quick smoke test

```bash
# Register
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"password123"}'
# → { "token": "eyJhbGc...", "tokenType": "Bearer" }

# Submit
TOKEN="eyJhbGc..."   # paste the token here
curl -X POST http://localhost:8080/api/v1/submissions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"languageId":1,"sourceCode":"print(\"Hello, CodeRank!\")"}'
# → 202 Accepted { "id": "abc-...", "status": "QUEUED", ... }

# Poll (replace <id>)
curl http://localhost:8080/api/v1/submissions/<id> \
  -H "Authorization: Bearer $TOKEN"
# → { "status": "SUCCESS", "stdout": "Hello, CodeRank!\n", "exitCode": 0, ... }
```

See **[API.md](./API.md)** for the complete endpoint reference.

---

## Frontend (optional)

A single-file React UI is included at `frontend/coderank.html`. No build step required.

1. Open `frontend/coderank.html` in your browser (double-click or drag into a tab).
2. Register or sign in. The JWT is stored in `localStorage`.
3. Pick a language, write code, click **Run**. The output panel updates live as the submission transitions through `QUEUED → RUNNING → SUCCESS/ERROR/TIMEOUT`.

The frontend talks to `http://localhost:8080`. CORS is configured server-side to accept local origins.

---

## Project structure

```
.
├── pom.xml
├── README.md                                  ← you are here
├── DESIGN.md                                  ← architecture, decisions, v2 roadmap
├── API.md                                     ← endpoint reference
├── frontend/
│   └── coderank.html                          ← optional single-file UI
└── src/
    ├── main/
    │   ├── java/com/coderank/coderank/
    │   │   ├── CoderankApplication.java       entry point; forces JVM TZ to UTC
    │   │   ├── config/
    │   │   │   ├── AsyncConfig.java           @EnableAsync + thread pool
    │   │   │   ├── DockerConfig.java          docker-java client bean
    │   │   │   └── SecurityConfig.java        Spring Security + CORS + filter wiring
    │   │   ├── controller/
    │   │   │   ├── AuthController.java
    │   │   │   ├── LanguageController.java
    │   │   │   ├── SubmissionController.java
    │   │   │   └── GlobalExceptionHandler.java
    │   │   ├── dto/
    │   │   │   ├── ApiError.java
    │   │   │   ├── AuthDtos.java
    │   │   │   ├── LanguageResponse.java
    │   │   │   └── SubmissionDtos.java
    │   │   ├── entity/
    │   │   │   ├── Language.java
    │   │   │   ├── Submission.java
    │   │   │   ├── SubmissionStatus.java
    │   │   │   └── User.java
    │   │   ├── repository/
    │   │   │   ├── LanguageRepository.java
    │   │   │   ├── SubmissionRepository.java
    │   │   │   └── UserRepository.java
    │   │   ├── security/
    │   │   │   ├── JwtAuthFilter.java
    │   │   │   ├── JwtService.java
    │   │   │   └── RateLimitFilter.java
    │   │   └── service/
    │   │       ├── AuthService.java
    │   │       ├── CodeExecutorService.java   the Docker lifecycle
    │   │       ├── ExecutionResult.java
    │   │       ├── SubmissionExecutionRunner.java
    │   │       └── SubmissionService.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql
    │           └── V2__seed_languages.sql
    └── test/                                  sparse v1 — see DESIGN.md "Known limitations"
```

---

## Further reading

- **[DESIGN.md](./DESIGN.md)** — full architecture, every major design decision with alternatives considered, security & concurrency models, and a detailed roadmap to v2 (with two warm-container approaches, the problems each raises, and how those problems are addressed).
- **[API.md](./API.md)** — every endpoint with request/response schemas, status codes, and curl examples.

---

## Acknowledgements

Built for the Airtribe Backend Engineering Launchpad case study. Mentor feedback shaped the design — particularly the discussion around container pre-warming, which is documented as the v2 roadmap in DESIGN.md.
