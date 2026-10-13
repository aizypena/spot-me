# SpotMe — API Reference

> Complete reference for the SpotMe REST API. All endpoints are versioned under `/api/v1`.

**Base URL (production):** `https://api.spotme.app/api/v1`
**Base URL (local):** `http://localhost:5000/api/v1`

For architecture, see [architecture.md](./architecture.md). For payment flows, see [stripe-flows.md](./stripe-flows.md).

---

## Table of Contents

- [1. Conventions](#1-conventions)
- [2. Authentication](#2-authentication)
- [3. Error Handling](#3-error-handling)
- [4. Pagination](#4-pagination)
- [5. Rate Limiting](#5-rate-limiting)
- [6. Data Types](#6-data-types)
- [7. Auth Endpoints](#7-auth-endpoints)
- [8. Trainer Endpoints](#8-trainer-endpoints)
- [9. Plan Endpoints](#9-plan-endpoints)
- [10. Package Endpoints](#10-package-endpoints)
- [11. Session Endpoints](#11-session-endpoints)
- [12. Booking Endpoints](#12-booking-endpoints)
- [13. Checkout Endpoints](#13-checkout-endpoints)
- [14. Billing Endpoints](#14-billing-endpoints)
- [15. Client (Me) Endpoints](#15-client-me-endpoints)
- [16. Progress Endpoints](#16-progress-endpoints)
- [17. Admin Endpoints](#17-admin-endpoints)
- [18. Webhook Endpoints](#18-webhook-endpoints)
- [19. Health & Metadata](#19-health--metadata)
- [20. Status Codes Summary](#20-status-codes-summary)

---

## 1. Conventions

### 1.1 Request format

- All requests use **JSON** (`Content-Type: application/json`) except file uploads.
- All responses are **JSON**.
- Timestamps are **ISO 8601 UTC** (e.g., `2025-03-14T15:09:26.535Z`).
- Monetary amounts are in **cents** (integer). `1500` = $15.00.
- IDs are **MongoDB ObjectId strings** unless prefixed with `cus_`, `sub_`, `pi_`, etc. (those are Stripe IDs).

### 1.2 Response format

**Success (single resource):**
```json
{
  "data": { "id": "...", "name": "..." }
}
```

**Success (collection):**
```json
{
  "data": [ { "id": "..." }, { "id": "..." } ],
  "pagination": {
    "total": 142,
    "page": 1,
    "limit": 20,
    "hasMore": true
  }
}
```

**Error:**
```json
{
  "error": {
    "code": "BOOKING_ALREADY_EXISTS",
    "message": "You already have a booking for this session.",
    "details": { "sessionId": "..." }
  }
}
```

### 1.3 Required headers

| Header | Value | When |
|---|---|---|
| `Authorization` | `Bearer <jwt>` | All authenticated endpoints |
| `Content-Type` | `application/json` | POST / PUT / PATCH |
| `Idempotency-Key` | UUID | Client-generated, recommended on POST for payment flows |

### 1.4 Environment base URLs

| Environment | URL |
|---|---|
| Local | `http://localhost:5000/api/v1` |
| Staging | `https://staging-api.spotme.app/api/v1` |
| Production | `https://api.spotme.app/api/v1` |

---

## 2. Authentication

### 2.1 Scheme

JWT bearer tokens. Two tokens are issued at login:

- **Access token** — short-lived (15 minutes). Sent in `Authorization: Bearer <token>`.
- **Refresh token** — long-lived (30 days). Sent to `POST /auth/refresh` to get a new access token.

### 2.2 Role model

| Role | Description |
|---|---|
| `client` | Books sessions, subscribes, buys packages |
| `trainer` | Creates plans, packages, manages availability |
| `admin` | Manages platform, issues refunds |

### 2.3 Token placement

| Client | Storage |
|---|---|
| Web | httpOnly cookie (preferred) or memory + refresh |
| Android | `EncryptedSharedPreferences` |
| iOS | Keychain |

Never store tokens in `localStorage` (web), `UserDefaults` (iOS), or plain `SharedPreferences` (Android).

### 2.4 Refreshing tokens

When an access token expires, the API returns `401` with `code: "TOKEN_EXPIRED"`. The client calls `POST /auth/refresh` with the refresh token to get a new pair. Refresh tokens are rotated on every use.

---

## 3. Error Handling

### 3.1 Error response shape

```json
{
  "error": {
    "code": "MACHINE_READABLE_CODE",
    "message": "Human-readable message.",
    "details": { "field": "reason" }
  }
}
```

### 3.2 Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Request body or params failed validation |
| 400 | `INVALID_JSON` | Malformed JSON in request body |
| 401 | `UNAUTHENTICATED` | Missing or invalid `Authorization` header |
| 401 | `TOKEN_EXPIRED` | Access token expired — refresh and retry |
| 401 | `INVALID_CREDENTIALS` | Wrong email or password |
| 403 | `FORBIDDEN` | Authenticated but not allowed |
| 403 | `ROLE_REQUIRED` | Endpoint requires a role you don't have |
| 404 | `NOT_FOUND` | Resource doesn't exist |
| 409 | `CONFLICT` | Resource already exists or state conflict |
| 409 | `SESSION_UNAVAILABLE` | Session already booked |
| 409 | `BOOKING_ALREADY_EXISTS` | User already booked this session |
| 409 | `SUBSCRIPTION_EXISTS` | User already has an active subscription |
| 409 | `NO_ACTIVE_SUBSCRIPTION` | Action requires an active subscription |
| 409 | `NO_PAYMENT_METHOD` | Off-session action requires a saved card |
| 402 | `PAYMENT_REQUIRED` | Payment needed to complete action |
| 402 | `PAYMENT_DECLINED` | Card was declined |
| 402 | `AUTHENTICATION_REQUIRED` | 3DS challenge needed; use returned `clientSecret` |
| 429 | `RATE_LIMITED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Unexpected server error |
| 502 | `STRIPE_ERROR` | Upstream Stripe error |
| 503 | `SERVICE_UNAVAILABLE` | Temporary outage |

### 3.3 Stripe error mapping

| Stripe code | SpotMe code | HTTP |
|---|---|---|
| `card_declined` | `PAYMENT_DECLINED` | 402 |
| `insufficient_funds` | `PAYMENT_DECLINED` | 402 |
| `expired_card` | `PAYMENT_DECLINED` | 402 |
| `authentication_required` | `AUTHENTICATION_REQUIRED` | 402 |
| `processing_error` | `STRIPE_ERROR` | 502 |
| `rate_limit` | `STRIPE_ERROR` | 502 |

---

## 4. Pagination

Collection endpoints support:

| Query | Type | Default | Max |
|---|---|---|---|
| `page` | integer | 1 | — |
| `limit` | integer | 20 | 100 |
| `sort` | string | varies | — |
| `order` | `asc` \| `desc` | `desc` | — |

Example:

```
GET /trainers?page=2&limit=50&sort=createdAt&order=desc
```

Response includes:

```json
{
  "data": [ ... ],
  "pagination": {
    "total": 142,
    "page": 2,
    "limit": 50,
    "hasMore": true
  }
}
```

---

## 5. Rate Limiting

Rate limits are applied per authenticated user (or per IP for unauthenticated requests).

| Scope | Limit |
|---|---|
| Global | 300 requests / minute |
| `/auth/login` | 10 requests / 15 minutes / IP |
| `/auth/register` | 5 requests / hour / IP |
| `/checkout/*` | 30 requests / minute / user |
| `/webhooks/stripe` | No limit (Stripe IPs only) |

When rate limited, the API returns `429` with headers:

```
Retry-After: 42
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1710432000
```

---

## 6. Data Types

### 6.1 User

```json
{
  "id": "6619a0c0a1b2c3d4e5f60001",
  "email": "alex@example.com",
  "name": "Alex Rivera",
  "role": "client",
  "stripeCustomerId": "cus_PqR8sT9uV0wX1yZ2",
  "defaultPaymentMethodId": "pm_1PqR8sT9uV0wX1yZ2",
  "createdAt": "2025-03-01T10:00:00.000Z"
}
```

### 6.2 Trainer

```json
{
  "id": "6619a0c0a1b2c3d4e5f60002",
  "userId": "6619a0c0a1b2c3d4e5f60001",
  "bio": "Certified strength coach, 10 years experience.",
  "specialties": ["strength", "mobility", "nutrition"],
  "rates": {
    "dropInCents": 4000,
    "noShowFeeCents": 2500,
    "depositCents": 2000
  },
  "availability": [
    { "day": "mon", "start": "06:00", "end": "12:00" },
    { "day": "wed", "start": "14:00", "end": "20:00" }
  ],
  "createdAt": "2025-03-01T10:00:00.000Z"
}
```

### 6.3 Plan

```json
{
  "id": "6619a0c0a1b2c3d4e5f60003",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "name": "Pro Membership",
  "description": "4 sessions per month with priority booking.",
  "stripePriceId": "price_1PqR8sT9uV0wX1yZ2",
  "interval": "month",
  "sessionsPerMonth": 4,
  "amountCents": 15000,
  "currency": "usd",
  "active": true,
  "createdAt": "2025-03-01T10:00:00.000Z"
}
```

### 6.4 Package

```json
{
  "id": "6619a0c0a1b2c3d4e5f60004",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "name": "10-Session Pack",
  "description": "10 one-on-one sessions, valid for 90 days.",
  "stripePriceId": "price_1PqR8sT9uV0wX1yZ2",
  "sessionCount": 10,
  "expiresAfterDays": 90,
  "amountCents": 35000,
  "currency": "usd",
  "active": true,
  "createdAt": "2025-03-01T10:00:00.000Z"
}
```

### 6.5 Session

```json
{
  "id": "6619a0c0a1b2c3d4e5f60005",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "startsAt": "2025-03-20T15:00:00.000Z",
  "durationMinutes": 60,
  "type": "one-on-one",
  "priceCents": 4000,
  "status": "available",
  "location": "SpotMe Studio, 123 Main St"
}
```

### 6.6 Booking

```json
{
  "id": "6619a0c0a1b2c3d4e5f60006",
  "sessionId": "6619a0c0a1b2c3d4e5f60005",
  "clientId": "6619a0c0a1b2c3d4e5f60001",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "paymentIntentId": "pi_3PqR8sT9uV0wX1yZ2",
  "status": "paid",
  "priceType": "drop-in",
  "createdAt": "2025-03-14T15:09:26.535Z"
}
```

### 6.7 Credit

```json
{
  "id": "6619a0c0a1b2c3d4e5f60007",
  "clientId": "6619a0c0a1b2c3d4e5f60001",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "source": "package",
  "sourceId": "6619a0c0a1b2c3d4e5f60004",
  "remaining": 7,
  "total": 10,
  "expiresAt": "2025-06-12T00:00:00.000Z",
  "createdAt": "2025-03-14T15:09:26.535Z"
}
```

### 6.8 Subscription

```json
{
  "id": "6619a0c0a1b2c3d4e5f60008",
  "clientId": "6619a0c0a1b2c3d4e5f60001",
  "planId": "6619a0c0a1b2c3d4e5f60003",
  "stripeSubscriptionId": "sub_1PqR8sT9uV0wX1yZ2",
  "status": "active",
  "currentPeriodEnd": "2025-04-14T15:09:26.535Z",
  "cancelAtPeriodEnd": false,
  "pauseCollection": null,
  "createdAt": "2025-03-14T15:09:26.535Z"
}
```

### 6.9 Charge

```json
{
  "id": "6619a0c0a1b2c3d4e5f60009",
  "clientId": "6619a0c0a1b2c3d4e5f60001",
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "type": "drop-in",
  "amountCents": 4000,
  "currency": "usd",
  "stripeId": "pi_3PqR8sT9uV0wX1yZ2",
  "status": "succeeded",
  "createdAt": "2025-03-14T15:09:26.535Z"
}
```

### 6.10 ProgressLog

```json
{
  "id": "6619a0c0a1b2c3d4e5f60010",
  "clientId": "6619a0c0a1b2c3d4e5f60001",
  "date": "2025-03-14T00:00:00.000Z",
  "metrics": {
    "weightKg": 78.4,
    "bodyFatPercent": 18.2,
    "restingHr": 62
  },
  "notes": "Felt strong on squats today.",
  "createdAt": "2025-03-14T15:09:26.535Z"
}
```

---

## 7. Auth Endpoints

### 7.1 `POST /auth/register`

Create a new user account.

**Auth:** none

**Request:**
```json
{
  "email": "alex@example.com",
  "password": "S3cure!Passw0rd",
  "name": "Alex Rivera",
  "role": "client"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `email` | string | ✅ | Valid email, unique |
| `password` | string | ✅ | Min 8 chars |
| `name` | string | ✅ | 1–80 chars |
| `role` | string | ❌ | `client` (default) \| `trainer` |

**Response `201`:**
```json
{
  "data": {
    "user": { "id": "...", "email": "...", "name": "...", "role": "client" },
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi..."
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `409 CONFLICT` (email taken), `429 RATE_LIMITED`

---

### 7.2 `POST /auth/login`

Authenticate and receive tokens.

**Auth:** none

**Request:**
```json
{
  "email": "alex@example.com",
  "password": "S3cure!Passw0rd"
}
```

**Response `200`:**
```json
{
  "data": {
    "user": { "id": "...", "email": "...", "name": "...", "role": "client" },
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi..."
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `401 INVALID_CREDENTIALS`, `429 RATE_LIMITED`

---

### 7.3 `POST /auth/refresh`

Exchange a refresh token for a new token pair. Refresh tokens are rotated.

**Auth:** none (uses refresh token in body)

**Request:**
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

**Response `200`:**
```json
{
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi..."
  }
}
```

**Errors:** `401 UNAUTHENTICATED`

---

### 7.4 `POST /auth/logout`

Revoke the current refresh token.

**Auth:** ✅

**Request:**
```json
{ "refreshToken": "eyJhbGciOi..." }
```

**Response `204`:** No content.

---

### 7.5 `GET /auth/me`

Return the authenticated user.

**Auth:** ✅

**Response `200`:**
```json
{
  "data": {
    "id": "...",
    "email": "alex@example.com",
    "name": "Alex Rivera",
    "role": "client",
    "stripeCustomerId": "cus_PqR8sT9uV0wX1yZ2",
    "defaultPaymentMethodId": "pm_1PqR8sT9uV0wX1yZ2"
  }
}
```

---

## 8. Trainer Endpoints

### 8.1 `GET /trainers`

List trainers with filters and pagination.

**Auth:** optional (public listing)

**Query:**
| Param | Type | Notes |
|---|---|---|
| `specialty` | string | Filter by specialty |
| `search` | string | Search name or bio |
| `page` | int | Default 1 |
| `limit` | int | Default 20, max 100 |

**Response `200`:**
```json
{
  "data": [ { "id": "...", "bio": "...", "specialties": [...] } ],
  "pagination": { "total": 42, "page": 1, "limit": 20, "hasMore": true }
}
```

---

### 8.2 `GET /trainers/:id`

Get a single trainer by ID.

**Auth:** optional

**Response `200`:**
```json
{ "data": { "id": "...", "bio": "...", "rates": {...}, "availability": [...] } }
```

**Errors:** `404 NOT_FOUND`

---

### 8.3 `POST /trainers`

Create a trainer profile for the authenticated user.

**Auth:** ✅ role `trainer`

**Request:**
```json
{
  "bio": "Certified strength coach.",
  "specialties": ["strength", "mobility"],
  "rates": { "dropInCents": 4000, "noShowFeeCents": 2500, "depositCents": 2000 },
  "availability": [
    { "day": "mon", "start": "06:00", "end": "12:00" }
  ]
}
```

**Response `201`:**
```json
{ "data": { "id": "...", "userId": "...", "bio": "..." } }
```

**Errors:** `401 UNAUTHENTICATED`, `403 ROLE_REQUIRED`, `409 CONFLICT`

---

### 8.4 `PUT /trainers/:id`

Update a trainer profile. Only the owning trainer can update.

**Auth:** ✅ role `trainer` (owner)

**Request:** any subset of trainer fields.

**Response `200`:**
```json
{ "data": { "id": "...", "bio": "...", "updatedAt": "..." } }
```

**Errors:** `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 NOT_FOUND`

---

## 9. Plan Endpoints

### 9.1 `GET /trainers/:id/plans`

List plans for a trainer.

**Auth:** optional

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "name": "Pro Membership",
      "interval": "month",
      "sessionsPerMonth": 4,
      "amountCents": 15000
    }
  ]
}
```

---

### 9.2 `POST /trainers/:id/plans`

Create a plan for a trainer. The API creates a Stripe Product + Price automatically.

**Auth:** ✅ role `trainer` (owner)

**Request:**
```json
{
  "name": "Pro Membership",
  "description": "4 sessions per month.",
  "interval": "month",
  "sessionsPerMonth": 4,
  "amountCents": 15000,
  "currency": "usd"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | ✅ | 1–80 chars |
| `description` | string | ❌ | Max 500 chars |
| `interval` | string | ✅ | `month` \| `year` |
| `sessionsPerMonth` | int | ✅ | 1–100 |
| `amountCents` | int | ✅ | Min 100 |
| `currency` | string | ❌ | Default `usd` |

**Response `201`:**
```json
{
  "data": {
    "id": "...",
    "name": "Pro Membership",
    "stripePriceId": "price_1PqR8sT9uV0wX1yZ2",
    "amountCents": 15000
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `403 FORBIDDEN`, `502 STRIPE_ERROR`

---

### 9.3 `PUT /plans/:id`

Update a plan. Changing `amountCents` creates a new Stripe Price and archives the old one.

**Auth:** ✅ role `trainer` (owner)

**Response `200`:** updated plan.

**Errors:** `403 FORBIDDEN`, `404 NOT_FOUND`

---

### 9.4 `DELETE /plans/:id`

Archive a plan. Existing subscriptions continue on the old price; new subscribers cannot select it.

**Auth:** ✅ role `trainer` (owner)

**Response `204`:** No content.

---

## 10. Package Endpoints

### 10.1 `GET /trainers/:id/packages`

List packages for a trainer.

**Auth:** optional

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "name": "10-Session Pack",
      "sessionCount": 10,
      "expiresAfterDays": 90,
      "amountCents": 35000
    }
  ]
}
```

---

### 10.2 `POST /trainers/:id/packages`

Create a package. Creates a Stripe Product + Price.

**Auth:** ✅ role `trainer` (owner)

**Request:**
```json
{
  "name": "10-Session Pack",
  "description": "10 sessions, valid 90 days.",
  "sessionCount": 10,
  "expiresAfterDays": 90,
  "amountCents": 35000,
  "currency": "usd"
}
```

**Response `201`:** created package.

**Errors:** `400 VALIDATION_FAILED`, `403 FORBIDDEN`

---

### 10.3 `PUT /packages/:id` / `DELETE /packages/:id`

Same semantics as plans.

---

## 11. Session Endpoints

### 11.1 `GET /trainers/:id/sessions`

List sessions for a trainer within a date range.

**Auth:** optional

**Query:**
| Param | Type | Notes |
|---|---|---|
| `from` | ISO date | Inclusive |
| `to` | ISO date | Exclusive |
| `status` | string | `available` \| `booked` \| `completed` \| `cancelled` |

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "startsAt": "2025-03-20T15:00:00.000Z",
      "durationMinutes": 60,
      "type": "one-on-one",
      "priceCents": 4000,
      "status": "available"
    }
  ]
}
```

---

### 11.2 `POST /sessions`

Create a session slot (trainer only).

**Auth:** ✅ role `trainer`

**Request:**
```json
{
  "startsAt": "2025-03-20T15:00:00.000Z",
  "durationMinutes": 60,
  "type": "one-on-one",
  "priceCents": 4000,
  "location": "SpotMe Studio, 123 Main St"
}
```

**Response `201`:** created session.

---

### 11.3 `GET /sessions/:id`

Get a session.

**Auth:** optional

**Response `200`:** session object.

---

### 11.4 `POST /sessions/:id/deposit`

Create a manual-capture PaymentIntent for a booking deposit.

**Auth:** ✅ role `client`

**Request:**
```json
{ "bookingId": "..." }
```

**Response `200`:**
```json
{ "data": { "clientSecret": "pi_3PqR8sT9uV0wX1yZ2_secret_..." } }
```

**Errors:** `402 PAYMENT_REQUIRED`, `409 CONFLICT`

---

### 11.5 `POST /sessions/:id/deposit/capture`

Capture a previously authorized deposit (admin or trainer, on no-show).

**Auth:** ✅ role `trainer` \| `admin`

**Response `200`:**
```json
{ "data": { "captured": true, "paymentIntentId": "pi_..." } }
```

---

### 11.6 `DELETE /sessions/:id`

Cancel a session. If there are paid bookings, triggers refunds.

**Auth:** ✅ role `trainer` (owner) \| `admin`

**Response `200`:** updated session with `status: "cancelled"`.

---

## 12. Booking Endpoints

### 12.1 `POST /bookings`

Create a booking for a session. Payment flow depends on the user's entitlements.

**Auth:** ✅ role `client`

**Request:**
```json
{
  "sessionId": "6619a0c0a1b2c3d4e5f60005",
  "useCredit": false
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `sessionId` | string | ✅ | Must be available |
| `useCredit` | boolean | ❌ | Default `false`. If `true`, consumes a credit if available |

**Response `201` (credit applied):**
```json
{
  "data": {
    "booking": { "id": "...", "status": "paid", "priceType": "package" },
    "requiresPayment": false,
    "creditRemaining": 6
  }
}
```

**Response `201` (payment required):**
```json
{
  "data": {
    "booking": { "id": "...", "status": "pending" },
    "requiresPayment": true,
    "clientSecret": "pi_3PqR8sT9uV0wX1yZ2_secret_..."
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `409 SESSION_UNAVAILABLE`, `409 BOOKING_ALREADY_EXISTS`

---

### 12.2 `GET /bookings/:id`

Get a booking.

**Auth:** ✅ (owner or trainer)

**Response `200`:** booking object.

---

### 12.3 `PUT /bookings/:id/reschedule`

Reschedule a booking to a different session.

**Auth:** ✅ role `client` (owner)

**Request:**
```json
{ "newSessionId": "6619a0c0a1b2c3d4e5f60011" }
```

**Response `200`:** updated booking.

**Errors:** `400 VALIDATION_FAILED`, `409 SESSION_UNAVAILABLE`

---

### 12.4 `DELETE /bookings/:id`

Cancel a booking. Applies the trainer's cancellation policy:

- More than 24h before start: no fee.
- Less than 24h: no-show fee charged off-session.

**Auth:** ✅ role `client` (owner)

**Response `200`:**
```json
{
  "data": {
    "booking": { "id": "...", "status": "cancelled" },
    "feeCharged": false
  }
}
```

**Response `200` (fee charged):**
```json
{
  "data": {
    "booking": { "id": "...", "status": "no-show" },
    "feeCharged": true,
    "chargeId": "..."
  }
}
```

**Response `402` (3DS required for fee):**
```json
{
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "Card authentication required to charge the no-show fee.",
    "details": { "clientSecret": "pi_..._secret_..." }
  }
}
```

**Errors:** `403 FORBIDDEN`, `404 NOT_FOUND`, `409 NO_PAYMENT_METHOD`

---

### 12.5 `POST /bookings/:id/no-show`

Mark a booking as no-show and charge the fee. Called by trainers.

**Auth:** ✅ role `trainer` (owner)

**Response `200`:**
```json
{
  "data": {
    "charged": true,
    "paymentIntentId": "pi_...",
    "amountCents": 2500
  }
}
```

**Errors:** `402 AUTHENTICATION_REQUIRED`, `409 NO_PAYMENT_METHOD`

---

## 13. Checkout Endpoints

### 13.1 `POST /checkout/subscription`

Create a Stripe Checkout Session for a membership plan.

**Auth:** ✅ role `client`

**Request:**
```json
{ "planId": "6619a0c0a1b2c3d4e5f60003" }
```

**Response `200`:**
```json
{
  "data": {
    "url": "https://checkout.stripe.com/c/pay/cs_test_...",
    "sessionId": "cs_test_..."
  }
}
```

The client opens `url` in a browser or webview. After payment, Stripe redirects to your `success_url`.

**Errors:** `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 SUBSCRIPTION_EXISTS`, `502 STRIPE_ERROR`

---

### 13.2 `POST /checkout/drop-in`

Create a PaymentIntent for a single drop-in session.

**Auth:** ✅ role `client`

**Request:**
```json
{ "sessionId": "6619a0c0a1b2c3d4e5f60005" }
```

**Response `200`:**
```json
{
  "data": {
    "clientSecret": "pi_3PqR8sT9uV0wX1yZ2_secret_...",
    "paymentIntentId": "pi_3PqR8sT9uV0wX1yZ2",
    "amountCents": 4000
  }
}
```

The client presents the Stripe PaymentSheet with this `clientSecret`.

**Errors:** `400 VALIDATION_FAILED`, `409 SESSION_UNAVAILABLE`, `502 STRIPE_ERROR`

---

### 13.3 `POST /checkout/package`

Create a Stripe Checkout Session for a package.

**Auth:** ✅ role `client`

**Request:**
```json
{ "packageId": "6619a0c0a1b2c3d4e5f60004" }
```

**Response `200`:**
```json
{
  "data": {
    "url": "https://checkout.stripe.com/c/pay/cs_test_...",
    "sessionId": "cs_test_..."
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `404 NOT_FOUND`

---

### 13.4 `POST /checkout/tip`

Create a PaymentIntent for tipping a trainer.

**Auth:** ✅ role `client`

**Request:**
```json
{
  "trainerId": "6619a0c0a1b2c3d4e5f60002",
  "amountCents": 1000
}
```

**Response `200`:**
```json
{
  "data": {
    "clientSecret": "pi_..._secret_...",
    "paymentIntentId": "pi_..."
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `404 NOT_FOUND`

---

## 14. Billing Endpoints

### 14.1 `POST /billing/setup-intent`

Create a SetupIntent to save a card for future off-session charges.

**Auth:** ✅

**Response `200`:**
```json
{
  "data": {
    "clientSecret": "seti_..._secret_..."
  }
}
```

Present the PaymentSheet in setup mode with this `clientSecret`.

---

### 14.2 `POST /billing-portal`

Create a Stripe Billing Portal session.

**Auth:** ✅

**Request:**
```json
{ "returnUrl": "https://app.spotme.app/dashboard/billing" }
```

**Response `200`:**
```json
{ "data": { "url": "https://billing.stripe.com/p/session/..." } }
```

The client opens `url` to let the user manage billing.

---

### 14.3 `POST /billing/pause`

Pause an active subscription.

**Auth:** ✅ role `client`

**Request:**
```json
{ "resumeAt": "2025-04-01T00:00:00.000Z" }
```

**Response `200`:**
```json
{
  "data": {
    "status": "active",
    "pauseCollection": {
      "behavior": "void",
      "resumesAt": "2025-04-01T00:00:00.000Z"
    }
  }
}
```

**Errors:** `409 NO_ACTIVE_SUBSCRIPTION`

---

### 14.4 `POST /billing/change-plan`

Upgrade or downgrade an active subscription with proration.

**Auth:** ✅ role `client`

**Request:**
```json
{ "newPlanId": "6619a0c0a1b2c3d4e5f60003" }
```

**Response `200`:**
```json
{
  "data": {
    "status": "active",
    "planId": "6619a0c0a1b2c3d4e5f60003"
  }
}
```

---

### 14.5 `POST /billing/cancel`

Cancel a subscription immediately or at period end.

**Auth:** ✅ role `client`

**Request:**
```json
{ "immediate": false }
```

**Response `200`:**
```json
{
  "data": {
    "status": "active",
    "cancelAtPeriodEnd": true
  }
}
```

---

## 15. Client (Me) Endpoints

### 15.1 `GET /me/subscriptions`

List the authenticated user's subscriptions.

**Auth:** ✅

**Query:** `status` filter (`active` \| `past_due` \| `canceled` \| `all`, default `active`)

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "planId": "...",
      "status": "active",
      "currentPeriodEnd": "2025-04-14T15:09:26.535Z",
      "cancelAtPeriodEnd": false
    }
  ]
}
```

---

### 15.2 `GET /me/credits`

List the authenticated user's credits.

**Auth:** ✅

**Query:**
| Param | Type | Notes |
|---|---|---|
| `trainerId` | string | Optional filter |
| `includeExpired` | boolean | Default `false` |

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "trainerId": "...",
      "source": "package",
      "remaining": 7,
      "total": 10,
      "expiresAt": "2025-06-12T00:00:00.000Z"
    }
  ]
}
```

---

### 15.3 `GET /me/bookings`

List the authenticated user's bookings.

**Auth:** ✅

**Query:**
| Param | Type | Notes |
|---|---|---|
| `status` | string | `pending` \| `paid` \| `completed` \| `cancelled` \| `no-show` |
| `from` | ISO date | Optional |
| `to` | ISO date | Optional |

**Response `200`:**
```json
{ "data": [ { "id": "...", "sessionId": "...", "status": "paid" } ] }
```

---

### 15.4 `GET /me/charges`

List the authenticated user's charge history.

**Auth:** ✅

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "type": "drop-in",
      "amountCents": 4000,
      "status": "succeeded",
      "createdAt": "2025-03-14T15:09:26.535Z"
    }
  ]
}
```

---

### 15.5 `GET /me/payment-methods`

List the authenticated user's saved payment methods.

**Auth:** ✅

**Response `200`:**
```json
{
  "data": [
    {
      "id": "pm_1PqR8sT9uV0wX1yZ2",
      "brand": "visa",
      "last4": "4242",
      "expMonth": 12,
      "expYear": 2026,
      "isDefault": true
    }
  ]
}
```

---

## 16. Progress Endpoints

### 16.1 `GET /me/progress`

List progress logs.

**Auth:** ✅ role `client`

**Query:** `from`, `to`, `limit`

**Response `200`:**
```json
{
  "data": [
    {
      "id": "...",
      "date": "2025-03-14T00:00:00.000Z",
      "metrics": { "weightKg": 78.4, "bodyFatPercent": 18.2 },
      "notes": "..."
    }
  ]
}
```

---

### 16.2 `POST /me/progress`

Create a progress log.

**Auth:** ✅ role `client`

**Request:**
```json
{
  "date": "2025-03-14T00:00:00.000Z",
  "metrics": { "weightKg": 78.4, "bodyFatPercent": 18.2, "restingHr": 62 },
  "notes": "Felt strong on squats today."
}
```

**Response `201`:** created progress log.

---

### 16.3 `GET /me/progress/analytics`

Subscriber-only analytics (trends, streaks, volume).

**Auth:** ✅ role `client` with active subscription

**Response `200`:**
```json
{
  "data": {
    "weightTrend": [{ "date": "2025-03-01", "value": 79.1 }, { "date": "2025-03-14", "value": 78.4 }],
    "sessionsCompleted": 8,
    "currentStreakDays": 12
  }
}
```

**Errors:** `403 FORBIDDEN`, `409 NO_ACTIVE_SUBSCRIPTION`

---

## 17. Admin Endpoints

All admin endpoints require `role: "admin"`.

### 17.1 `POST /admin/refunds`

Issue a refund for a PaymentIntent.

**Auth:** ✅ role `admin`

**Request:**
```json
{
  "paymentIntentId": "pi_3PqR8sT9uV0wX1yZ2",
  "amountCents": 4000,
  "reason": "requested_by_customer"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `paymentIntentId` | string | ✅ | The Stripe PaymentIntent |
| `amountCents` | int | ❌ | Omit for full refund |
| `reason` | string | ❌ | `duplicate` \| `fraudulent` \| `requested_by_customer` |

**Response `200`:**
```json
{
  "data": {
    "refundId": "re_3PqR8sT9uV0wX1yZ2",
    "status": "succeeded",
    "amountCents": 4000
  }
}
```

**Errors:** `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `502 STRIPE_ERROR`

---

### 17.2 `GET /admin/charges`

List all charges with filters.

**Auth:** ✅ role `admin`

**Query:** `clientId`, `trainerId`, `type`, `status`, `from`, `to`, `page`, `limit`

**Response `200`:** paginated list of charges.

---

### 17.3 `GET /admin/webhook-events`

List processed webhook events (for debugging).

**Auth:** ✅ role `admin`

**Query:** `type`, `from`, `to`, `page`, `limit`

**Response `200`:**
```json
{
  "data": [
    {
      "eventId": "evt_1PqR8sT9uV0wX1yZ2",
      "type": "payment_intent.succeeded",
      "processedAt": "2025-03-14T15:09:26.535Z"
    }
  ]
}
```

---

### 17.4 `POST /admin/impersonate/:userId`

Issue a short-lived token as another user (for support).

**Auth:** ✅ role `admin`

**Response `200`:**
```json
{
  "data": {
    "accessToken": "eyJhbGciOi...",
    "expiresIn": 300
  }
}
```

---

## 18. Webhook Endpoints

### 18.1 `POST /webhooks/stripe`

Receive Stripe webhook events. **Not for clients** — only Stripe calls this.

**Auth:** Stripe signature verification (`Stripe-Signature` header)

**Important:** The body must be the **raw buffer**, not parsed JSON. Configure the route with `express.raw({ type: 'application/json' })`.

**Request:** Stripe event JSON (signed)

**Response `200`:**
```json
{ "received": true }
```

If the event has already been processed, returns:
```json
{ "received": true, "skipped": true }
```

**Errors:** `400 INVALID_SIGNATURE` (signature mismatch), `500 INTERNAL_ERROR` (triggers Stripe retry)

**Handled event types:**

| Event | Action |
|---|---|
| `checkout.session.completed` | Fulfill package / activate subscription |
| `customer.subscription.created` | Insert local subscription |
| `customer.subscription.updated` | Sync status, plan, pause, cancel_at_period_end |
| `customer.subscription.deleted` | Mark canceled |
| `invoice.payment_succeeded` | Extend period, grant credits |
| `invoice.payment_failed` | Mark past_due, notify user |
| `payment_intent.succeeded` | Fulfill drop-in, tip, no-show fee |
| `payment_intent.payment_failed` | Mark failed, notify user |
| `charge.refunded` | Revoke entitlements |
| `setup_intent.succeeded` | Save payment method |

---

## 19. Health & Metadata

### 19.1 `GET /healthz`

Liveness check.

**Auth:** none

**Response `200`:**
```json
{ "status": "ok", "uptime": 12345, "timestamp": "2025-03-14T15:09:26.535Z" }
```

---

### 19.2 `GET /readyz`

Readiness check — verifies MongoDB and Stripe connectivity.

**Auth:** none

**Response `200`:**
```json
{
  "status": "ready",
  "checks": {
    "mongodb": "ok",
    "stripe": "ok"
  }
}
```

**Response `503`:**
```json
{
  "status": "not_ready",
  "checks": {
    "mongodb": "ok",
    "stripe": "error"
  }
}
```

---

### 19.3 `GET /version`

Returns API version and build info.

**Auth:** none

**Response `200`:**
```json
{
  "data": {
    "apiVersion": "v1",
    "build": "1.0.0",
    "commit": "a1b2c3d",
    "builtAt": "2025-03-14T12:00:00.000Z"
  }
}
```

---

## 20. Status Codes Summary

| Code | Meaning | Typical cause |
|---|---|---|
| 200 | OK | Successful GET / PUT |
| 201 | Created | Successful POST creating a resource |
| 204 | No Content | Successful DELETE or logout |
| 400 | Bad Request | Validation failed, malformed JSON |
| 401 | Unauthorized | Missing/invalid/expired token |
| 402 | Payment Required | Card declined, 3DS needed |
| 403 | Forbidden | Authenticated but not allowed |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Duplicate or state conflict |
| 429 | Too Many Requests | Rate limited |
| 500 | Internal Server Error | Unexpected server bug |
| 502 | Bad Gateway | Upstream Stripe error |
| 503 | Service Unavailable | Temporary outage |

---

## Appendix A — Authentication Quick Reference

| Endpoint | Auth | Role |
|---|---|---|
| `POST /auth/register` | ❌ | — |
| `POST /auth/login` | ❌ | — |
| `POST /auth/refresh` | ❌ | — |
| `POST /auth/logout` | ✅ | any |
| `GET /auth/me` | ✅ | any |
| `GET /trainers` | ❌ | — |
| `GET /trainers/:id` | ❌ | — |
| `POST /trainers` | ✅ | trainer |
| `PUT /trainers/:id` | ✅ | trainer (owner) |
| `GET /trainers/:id/plans` | ❌ | — |
| `POST /trainers/:id/plans` | ✅ | trainer (owner) |
| `PUT /plans/:id` | ✅ | trainer (owner) |
| `DELETE /plans/:id` | ✅ | trainer (owner) |
| `GET /trainers/:id/packages` | ❌ | — |
| `POST /trainers/:id/packages` | ✅ | trainer (owner) |
| `GET /trainers/:id/sessions` | ❌ | — |
| `POST /sessions` | ✅ | trainer |
| `GET /sessions/:id` | ❌ | — |
| `POST /sessions/:id/deposit` | ✅ | client |
| `POST /sessions/:id/deposit/capture` | ✅ | trainer / admin |
| `DELETE /sessions/:id` | ✅ | trainer (owner) / admin |
| `POST /bookings` | ✅ | client |
| `GET /bookings/:id` | ✅ | owner / trainer |
| `PUT /bookings/:id/reschedule` | ✅ | client (owner) |
| `DELETE /bookings/:id` | ✅ | client (owner) |
| `POST /bookings/:id/no-show` | ✅ | trainer (owner) |
| `POST /checkout/subscription` | ✅ | client |
| `POST /checkout/drop-in` | ✅ | client |
| `POST /checkout/package` | ✅ | client |
| `POST /checkout/tip` | ✅ | client |
| `POST /billing/setup-intent` | ✅ | any |
| `POST /billing-portal` | ✅ | any |
| `POST /billing/pause` | ✅ | client |
| `POST /billing/change-plan` | ✅ | client |
| `POST /billing/cancel` | ✅ | client |
| `GET /me/subscriptions` | ✅ | client |
| `GET /me/credits` | ✅ | client |
| `GET /me/bookings` | ✅ | client |
| `GET /me/charges` | ✅ | client |
| `GET /me/payment-methods` | ✅ | any |
| `GET /me/progress` | ✅ | client |
| `POST /me/progress` | ✅ | client |
| `GET /me/progress/analytics` | ✅ | client (subscriber) |
| `POST /admin/refunds` | ✅ | admin |
| `GET /admin/charges` | ✅ | admin |
| `GET /admin/webhook-events` | ✅ | admin |
| `POST /admin/impersonate/:userId` | ✅ | admin |
| `POST /webhooks/stripe` | Stripe sig | — |
| `GET /healthz` | ❌ | — |
| `GET /readyz` | ❌ | — |
| `GET /version` | ❌ | — |

---

## Appendix B — Endpoint-to-Flow Map

| Flow | Endpoints |
|---|---|
| Register and log in | `POST /auth/register`, `POST /auth/login`, `GET /auth/me` |
| Browse trainers | `GET /trainers`, `GET /trainers/:id` |
| Subscribe to a trainer | `POST /checkout/subscription`, `GET /me/subscriptions`, `POST /billing-portal` |
| Buy a package | `POST /checkout/package`, `GET /me/credits` |
| Book a drop-in | `POST /checkout/drop-in`, `POST /bookings`, `GET /me/bookings` |
| Tip a trainer | `POST /checkout/tip`, `GET /me/charges` |
| Save a card | `POST /billing/setup-intent`, `GET /me/payment-methods` |
| No-show fee | `DELETE /bookings/:id`, `POST /bookings/:id/no-show` |
| Deposit | `POST /sessions/:id/deposit`, `POST /sessions/:id/deposit/capture` |
| Pause membership | `POST /billing/pause`, `GET /me/subscriptions` |
| Change plan | `POST /billing/change-plan`, `GET /me/subscriptions` |
| Cancel subscription | `POST /billing/cancel`, `GET /me/subscriptions` |
| Track progress | `POST /me/progress`, `GET /me/progress`, `GET /me/progress/analytics` |
| Issue refund | `POST /admin/refunds`, `GET /admin/charges` |

---

<p align="center">
  <em>Everything the client needs to know, and nothing it shouldn't.</em>
</p>
