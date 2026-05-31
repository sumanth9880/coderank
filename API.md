# CodeRank — API Reference

Complete reference for the CodeRank v1 REST API. For higher-level architecture and design rationale, see [DESIGN.md](./DESIGN.md).

---

## Table of contents

- [Base URL and conventions](#base-url-and-conventions)
- [Authentication](#authentication)
- [Error format](#error-format)
- [Rate limiting](#rate-limiting)
- [Endpoints](#endpoints)
  - [POST /api/v1/auth/register](#post-apiv1authregister)
  - [POST /api/v1/auth/login](#post-apiv1authlogin)
  - [GET /api/v1/languages](#get-apiv1languages)
  - [POST /api/v1/submissions](#post-apiv1submissions)
  - [GET /api/v1/submissions/{id}](#get-apiv1submissionsid)
  - [GET /api/v1/submissions](#get-apiv1submissions)
- [Reference: submission status values](#reference-submission-status-values)

---

## Base URL and conventions

- **Base URL (local):** `http://localhost:8080`
- **Content type:** All request and response bodies are JSON (`application/json`).
- **Character encoding:** UTF-8.
- **Timestamps:** ISO-8601 in UTC (e.g. `2026-05-28T19:31:57.544Z`).
- **IDs:** `users.id` and `submissions.id` are UUIDs (string). `languages.id` is a long integer.

---

## Authentication

All endpoints except registration, login, and `GET /api/v1/languages` require a JWT, sent as:

```
Authorization: Bearer <token>
```

You obtain a token from `POST /api/v1/auth/register` or `POST /api/v1/auth/login`. Tokens are HS384-signed JWTs with a default TTL of **24 hours**.

**Token payload (informational; clients don't need to read it):**

```json
{
  "sub": "537b70a1-e9fe-41a6-aaca-bf563a61535b",   // user UUID
  "email": "demo@example.com",
  "iat": 1779994325,
  "exp": 1780080725
}
```

Requests with a missing, malformed, or expired token receive **401** (or **403** for forwarded error dispatch — see [DESIGN.md](./DESIGN.md) §7).

---

## Error format

Every error response — validation, not-found, rate-limit, internal — uses the same JSON envelope:

```json
{
  "timestamp": "2026-05-28T19:31:57.544Z",
  "status": 404,
  "error": "Not Found",
  "message": "Submission not found",
  "path": "/api/v1/submissions/abc-..."
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `timestamp` | string (ISO-8601 UTC) | When the error was generated server-side |
| `status` | integer | HTTP status code |
| `error` | string | Reason phrase (e.g. "Bad Request", "Not Found") |
| `message` | string | Human-readable explanation; for validation errors lists the failed fields |
| `path` | string | The request URI |

---

## Rate limiting

`POST /api/v1/submissions` is rate-limited **per authenticated user** to **10 requests per minute** using a token bucket (Bucket4j). Bursts up to the full capacity are allowed; the bucket refills smoothly over the minute.

When the limit is exceeded, the API returns **429 Too Many Requests** with this body:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again shortly."
}
```

All other endpoints are not rate-limited in v1.

---

## Endpoints

### POST /api/v1/auth/register

Create a new user account and receive a JWT.

- **Auth required:** no
- **Idempotent:** no

**Request body:**

```json
{
  "email": "demo@example.com",
  "password": "password123"
}
```

| Field | Type | Constraints |
| --- | --- | --- |
| `email` | string | Required. Valid email format. Must be unique. |
| `password` | string | Required. Length 8–100. |

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `200 OK` | Success | `AuthResponse` (see below) |
| `400 Bad Request` | Validation failure (bad email, password too short, missing field) | `ApiError` |
| `409 Conflict` | Email already registered | `ApiError` with `"message": "Email already registered"` |

**Success body — `AuthResponse`:**

```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9.eyJzdWIi...",
  "tokenType": "Bearer"
}
```

**Example:**

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"password123"}'
```

---

### POST /api/v1/auth/login

Exchange credentials for a fresh JWT.

- **Auth required:** no
- **Idempotent:** yes (multiple logins issue new tokens; old tokens remain valid until expiry)

**Request body:**

```json
{
  "email": "demo@example.com",
  "password": "password123"
}
```

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `200 OK` | Success | `AuthResponse` |
| `400 Bad Request` | Validation failure | `ApiError` |
| `401 Unauthorized` | Wrong email or wrong password (unified — never leaks which) | `ApiError` with `"message": "Invalid credentials"` |

**Example:**

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"password123"}'
```

---

### GET /api/v1/languages

List the languages this CodeRank instance supports.

- **Auth required:** no (so an unauthenticated UI can populate a language dropdown)
- **Idempotent:** yes

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `200 OK` | Always | Array of `LanguageResponse` |

**Body — `LanguageResponse[]`:**

```json
[
  { "id": 1, "name": "python", "version": "3.11" }
]
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | integer | Use this as `languageId` when creating a submission. |
| `name` | string | Language name (lowercase). |
| `version` | string | Language version. |

> The internal `dockerImage`, `sourceFile`, and `runCommand` columns are deliberately *not* exposed — they are execution details, not part of the API contract.

**Example:**

```bash
curl http://localhost:8080/api/v1/languages
```

---

### POST /api/v1/submissions

Submit code for execution. The API saves the submission with `status = QUEUED`, dispatches execution to a background thread, and returns immediately. Poll [GET /api/v1/submissions/{id}](#get-apiv1submissionsid) for the result.

- **Auth required:** yes
- **Idempotent:** no
- **Rate-limited:** yes (10/minute/user)

**Request body — `CreateSubmissionRequest`:**

```json
{
  "languageId": 1,
  "sourceCode": "print(\"Hello, CodeRank!\")",
  "stdin": null
}
```

| Field | Type | Constraints |
| --- | --- | --- |
| `languageId` | integer | Required. Must reference a row in `languages`. |
| `sourceCode` | string | Required, non-blank, max 100,000 characters (~100 KB). |
| `stdin` | string \| null | Optional, max 10,000 characters (~10 KB). *Note: stdin is stored but not piped into the container in v1 — see [DESIGN.md](./DESIGN.md) §9.* |

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `202 Accepted` | Submission persisted, execution dispatched | `SubmissionResponse` |
| `400 Bad Request` | Validation failure (blank source, oversized, missing field, unknown `languageId`) | `ApiError` |
| `401 Unauthorized` | Missing/invalid token | `ApiError` |
| `429 Too Many Requests` | Per-user rate limit exceeded | `ApiError` |

**Success body — `SubmissionResponse`:**

```json
{
  "id": "b31fdf16-673b-4877-92ca-047d84250a14",
  "language": "python 3.11",
  "status": "QUEUED",
  "stdout": null,
  "stderr": null,
  "exitCode": null,
  "execTimeMs": null,
  "memoryKb": null,
  "createdAt": "2026-05-28T19:12:37.048Z",
  "completedAt": null
}
```

See [Reference: submission status values](#reference-submission-status-values) for what each `status` means.

**Example:**

```bash
curl -X POST http://localhost:8080/api/v1/submissions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"languageId":1,"sourceCode":"print(\"hi\")"}'
```

---

### GET /api/v1/submissions/{id}

Fetch a single submission by ID. Used for polling status until it reaches a terminal state (`SUCCESS`, `ERROR`, or `TIMEOUT`).

- **Auth required:** yes
- **Idempotent:** yes (safe to poll)

**Path parameters:**

| Name | Type | Notes |
| --- | --- | --- |
| `id` | UUID | The submission ID returned by `POST /submissions`. |

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `200 OK` | Submission found and belongs to the requesting user | `SubmissionResponse` |
| `401 Unauthorized` | Missing/invalid token | `ApiError` |
| `404 Not Found` | No submission with that ID *for the requesting user* (also returned if the submission belongs to a different user, to avoid leaking existence) | `ApiError` |

**Success body — `SubmissionResponse` (terminal state):**

```json
{
  "id": "b31fdf16-673b-4877-92ca-047d84250a14",
  "language": "python 3.11",
  "status": "SUCCESS",
  "stdout": "Hello, CodeRank!\n",
  "stderr": "",
  "exitCode": 0,
  "execTimeMs": 1699,
  "memoryKb": null,
  "createdAt": "2026-05-28T19:12:37.048Z",
  "completedAt": "2026-05-28T19:12:39.043Z"
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID string | Stable across all states. |
| `language` | string | Human-readable `"name version"` (e.g. `"python 3.11"`). |
| `status` | string enum | `QUEUED`, `RUNNING`, `SUCCESS`, `ERROR`, `TIMEOUT`. |
| `stdout` / `stderr` | string \| null | Filled once execution completes. May be empty strings. |
| `exitCode` | integer \| null | Container exit code. `null` if status is `TIMEOUT` or pre-terminal. |
| `execTimeMs` | long \| null | Wall-clock execution time. Includes container spin-up and teardown overhead. |
| `memoryKb` | long \| null | Always `null` in v1 — peak memory reporting is on the roadmap. |
| `createdAt` | timestamp | When the submission was queued. |
| `completedAt` | timestamp \| null | When the submission reached a terminal state. |

**Example:**

```bash
curl http://localhost:8080/api/v1/submissions/<id> \
  -H "Authorization: Bearer $TOKEN"
```

---

### GET /api/v1/submissions

List the current user's submissions, newest first. Other users' submissions are never returned, regardless of pagination parameters.

- **Auth required:** yes
- **Idempotent:** yes

**Query parameters:**

| Name | Type | Default | Notes |
| --- | --- | --- | --- |
| `page` | integer | `0` | Zero-indexed page number. |
| `size` | integer | `20` | Items per page. Capped at 100 server-side regardless of input. |

**Responses:**

| Code | When | Body |
| --- | --- | --- |
| `200 OK` | Always (may be empty array) | `SubmissionResponse[]` |
| `401 Unauthorized` | Missing/invalid token | `ApiError` |

**Body shape:** array of `SubmissionResponse` objects, identical to the single-item endpoint, ordered by `createdAt` descending.

**Example:**

```bash
curl 'http://localhost:8080/api/v1/submissions?page=0&size=10' \
  -H "Authorization: Bearer $TOKEN"
```

---

## Reference: submission status values

| Status | Meaning | Terminal? |
| --- | --- | --- |
| `QUEUED` | Persisted; not yet picked up by an executor thread. | No |
| `RUNNING` | An executor thread has started the container; user code is executing. | No |
| `SUCCESS` | Container exited with code 0 within the timeout. | **Yes** |
| `ERROR` | Container exited with a non-zero code (uncaught exception, runtime error, etc.). | **Yes** |
| `TIMEOUT` | The 10-second wall-clock limit elapsed before the code finished; the container was killed. | **Yes** |

Clients should poll until `status` is one of `SUCCESS`, `ERROR`, or `TIMEOUT`. The bundled frontend polls every 1.2 seconds with a brief exponential backoff if a poll fails.
