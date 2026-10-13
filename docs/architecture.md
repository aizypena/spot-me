# SpotMe — Architecture

> Technical architecture for SpotMe, a fitness coaching platform built with **React + TypeScript** (web), **Express + TypeScript** (API), **Java** (Android), **Swift** (iOS), **MongoDB**, and **Stripe**.

This document explains **how** the system is built and **why** each decision was made. For setup instructions, see [README.md](./README.md).

---

## Table of Contents

- [1. Architectural Goals](#1-architectural-goals)
- [2. System Overview](#2-system-overview)
- [3. Architectural Style](#3-architectural-style)
- [4. Component Breakdown](#4-component-breakdown)
- [5. Backend Architecture](#5-backend-architecture)
- [6. Client Architecture](#6-client-architecture)
- [7. Data Architecture](#7-data-architecture)
- [8. Stripe Integration Architecture](#8-stripe-integration-architecture)
- [9. Authentication & Authorization](#9-authentication--authorization)
- [10. API Contract & Versioning](#10-api-contract--versioning)
- [11. Error Handling & Resilience](#11-error-handling--resilience)
- [12. Security Architecture](#12-security-architecture)
- [13. Observability](#13-observability)
- [14. Scalability & Performance](#14-scalability--performance)
- [15. Deployment Topology](#15-deployment-topology)
- [16. Key Design Decisions](#16-key-design-decisions)
- [17. Trade-offs & Alternatives Considered](#17-trade-offs--alternatives-considered)
- [18. Future Evolution](#18-future-evolution)

---

## 1. Architectural Goals

The architecture is optimized for these priorities, in order:

1. **Correctness of payment state.** Money is involved. Payment state must be derived from Stripe webhooks, not client claims.
2. **Single source of truth for business logic.** All rules (credits, cancellations, entitlements) live in the API, not duplicated across clients.
3. **Cross-platform parity.** Web, Android, and iOS must expose the same capabilities against the same contract.
4. **Testability.** Business logic must be unit-testable without spinning up HTTP servers or Stripe.
5. **Iterability.** Adding a payment flow should not require rewriting clients.
6. **Security by default.** Secrets never leave the server. Clients never see `sk_*` keys.

Non-goals for v1:

- Multi-region active-active deployment
- Microservices
- Real-time websockets (polling/refetch is sufficient)
- Server-side rendering for the web app

---

## 2. System Overview

```
┌───────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS                                      │
│                                                                           │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐    │
│  │  React Web  │   │  Android    │   │    iOS      │   │  Admin Web  │    │
│  │    (TS)     │   │   (Java)    │   │   (Swift)   │   │    (TS)     │    │
│  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘    │
│         │                 │                 │                 │           │
└─────────┼─────────────────┼─────────────────┼─────────────────┼───────────┘
          │                 │                 │                 │
          │      HTTPS / REST (JSON) + JWT Bearer Token         │
          │                 │                 │                 │
          └─────────────────┴────────┬────────┴─────────────────┘
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                         EXPRESS + TYPESCRIPT API                          │
│                                                                           │
│   Routes → Controllers → Services → Models                                │
│                                                                           │
│   Webhooks → Handlers → Services                                          │
│                                                                           │
└─────────┬────────────────┬────────────────┬───────────────────────────────┘
          │                │                │
          ▼                ▼                ▼
   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
   │  MongoDB    │  │   Stripe    │  │  Stripe     │
   │  (Atlas)    │  │   API       │  │  Webhooks   │
   └─────────────┘  └─────────────┘  └─────────────┘
```

**Trust boundaries:**

- **Untrusted:** all client input, including client-claimed payment status.
- **Trusted:** Stripe webhook payloads (after signature verification).
- **Authoritative for payment state:** Stripe. MongoDB is a *projection* of Stripe state plus local business data.

---

## 3. Architectural Style

SpotMe uses a **layered monolith** for the backend and **client-side MVVM** for each frontend. This is deliberate.

### Why a monolith, not microservices

- A single team owns the whole system.
- Payments, bookings, and credits are tightly coupled — a distributed transaction between "charge succeeded" and "credit granted" would be a liability.
- Deployment simplicity matters more than independent scaling at this stage.
- The API can be split later along clear seams (auth, billing, bookings) if needed.

### Why layered, not vertical slices

- Payment logic is cross-cutting (checkout, webhooks, credits, subscriptions). A layered approach keeps this logic centralized.
- Controllers must stay thin so they can be reused across REST and (future) gRPC or GraphQL layers.
- Services are the unit of business logic; they must not depend on Express types.

### Why MVVM on clients

- Native UI frameworks (Android Views, SwiftUI) map cleanly to View + ViewModel.
- ViewModels are testable without UI.
- Repositories isolate network and cache concerns from UI state.
- The same conceptual model (View → ViewModel → Repository → API) exists on Android and iOS, so debugging across platforms is easier.

---

## 4. Component Breakdown

| Component | Responsibility | Technology |
|---|---|---|
| Web Client | Public site, trainer directory, client/trainer/admin dashboards | React, TypeScript, Vite, Tailwind |
| Android Client | Native client for clients and trainers | Java, Android SDK, Retrofit, Room |
| iOS Client | Native client for clients and trainers | Swift, SwiftUI, URLSession, SwiftData |
| API | Business logic, persistence, Stripe integration, webhooks | Node.js, Express, TypeScript, Mongoose |
| Database | Persistent storage | MongoDB (Atlas) |
| Payment Processor | Charges, subscriptions, refunds, payouts | Stripe |
| API Contract | Cross-platform agreement | OpenAPI 3.0 |
| Push | Session reminders and payment notifications | FCM (Android), APNs (iOS) |

---

## 5. Backend Architecture

### 5.1 Layer responsibilities

```
HTTP Request
    │
    ▼
┌──────────────┐
│  Middleware  │  → auth, rate limit, validation, logging
└──────┬───────┘
       ▼
┌──────────────┐
│   Routes     │  → URL → controller mapping, no logic
└──────┬───────┘
       ▼
┌──────────────┐
│ Controllers  │  → parse req, call service, shape response
└──────┬───────┘
       ▼
┌──────────────┐
│  Services    │  → business rules, orchestration, Stripe calls
└──────┬───────┘
       ▼
┌──────────────┐
│   Models     │  → Mongoose schemas, data access
└──────────────┘
```

**Rule:** a controller may only call services and shape responses. It may not touch Mongoose or Stripe directly. A service may only touch models, Stripe, and other services. It may not import from `express`.

### 5.2 Why this layering

- **Testability.** Services are pure TypeScript functions/classes. They can be tested with mocked repositories and a mocked Stripe client.
- **Swappability.** Stripe can be replaced with another processor by rewriting `stripe.service.ts` and the webhook handlers.
- **Reuse.** The same `bookingService.createBooking()` can be invoked from a REST controller, a webhook handler, or a future cron job.

### 5.3 Webhook handling

Webhook handling is a **separate entry point** into the same service layer:

```
Stripe → POST /api/v1/webhooks/stripe
              │
              ▼
       ┌──────────────┐
       │  Raw body    │  → express.raw(), not express.json()
       │  middleware  │
       └──────┬───────┘
              ▼
       ┌──────────────┐
       │  Signature   │  → stripe.webhooks.constructEvent()
       │  verification│
       └──────┬───────┘
              ▼
       ┌──────────────┐
       │ Idempotency  │  → check WebhookEvent collection
       │  guard       │
       └──────┬───────┘
              ▼
       ┌──────────────┐
       │  Dispatcher  │  → switch on event.type
       └──────┬───────┘
              ▼
       ┌──────────────┐
       │  Handler     │  → calls services, updates DB
       └──────┬───────┘
              ▼
       ┌──────────────┐
       │  Mark event  │  → store event.id to prevent replay
       │  processed   │
       └──────────────┘
```

Every webhook handler must be **idempotent** — Stripe retries failed deliveries, and the same event can arrive multiple times.

### 5.4 Service layer responsibilities

| Service | Owns |
|---|---|
| `auth.service` | Registration, login, JWT issuance, password hashing |
| `trainer.service` | Trainer profiles, availability |
| `plan.service` | Membership plan CRUD, Stripe Product/Price creation |
| `package.service` | Session package CRUD, Stripe Product/Price creation |
| `credits.service` | Credit ledger: grant, decrement, expire |
| `booking.service` | Session booking, rescheduling, cancellation policy |
| `subscription.service` | Local subscription mirror of Stripe state |
| `stripe.service` | Thin wrapper around Stripe SDK — the *only* place Stripe is called |
| `webhook.service` | Dispatch + idempotency |
| `notification.service` | Emails and push (post-v1) |

### 5.5 Why a single `stripe.service`

All Stripe calls funnel through one module. This means:

- One place to add retries, timeouts, or logging.
- One place to add an idempotency key policy.
- Easy to mock in tests.
- Easy to swap Stripe for a different processor.

---

## 6. Client Architecture

### 6.1 Web (React + TypeScript)

**Pattern:** feature-sliced + local state per feature, with React Query for server state.

```
Page/Route
    │
    ▼
Feature Component
    │
    ▼
React Query hook (useQuery / useMutation)
    │
    ▼
API client (axios + JWT interceptor)
    │
    ▼
Express API
```

**Why:**

- React Query handles caching, refetching, and optimistic updates — critical when webhooks update state server-side and the client needs to catch up.
- Feature slices keep payment logic, booking logic, and trainer logic isolated.
- Stripe Elements is isolated in `components/stripe/` so it can be reused across checkout flows.

### 6.2 Android (Java)

**Pattern:** MVVM + Repository, single Activity with Fragments (or multiple Activities).

```
Fragment (View)
    │ observes LiveData / StateFlow
    ▼
ViewModel
    │ calls
    ▼
Repository
    │  ├── ApiService (Retrofit)  → Express API
    │  └── Dao (Room)             → local cache
    ▼
Local DB / Network
```

**Why:**

- ViewModel survives configuration changes, so payment state isn't lost on rotation.
- Repository centralizes the "network vs cache" decision.
- Retrofit + OkHttp handles JWT via an interceptor.
- Room caches trainers and sessions for offline viewing.

**Stripe:** `PaymentSheet` is presented from a Fragment, initialized with a `client_secret` fetched from the API. The SDK handles card entry and confirmation.

### 6.3 iOS (Swift)

**Pattern:** MVVM + Repository, SwiftUI, async/await.

```
SwiftUI View
    │ observes @Observable ViewModel
    ▼
ViewModel
    │ calls
    ▼
Repository
    │  ├── APIClient (URLSession) → Express API
    │  └── CacheStore (SwiftData) → local cache
    ▼
Local DB / Network
```

**Why:**

- SwiftUI's `@Observable` gives clean, reactive state without boilerplate.
- async/await makes network code readable and cancellable.
- Keychain stores JWT — never `UserDefaults`.
- `PaymentSheet` is presented from the ViewModel via a small `StripeService` wrapper.

### 6.4 Client parity

All three clients:

- Talk to the same `/api/v1` endpoints.
- Use the same JWT scheme.
- Present the same Stripe flows (Checkout for subscriptions and packages, PaymentSheet for one-time payments).
- Refetch server state after payment confirmation, rather than trusting client-side success.

---

## 7. Data Architecture

### 7.1 MongoDB as a projection

MongoDB is **not** the source of truth for payment state. It is a **local projection** of Stripe state plus SpotMe-specific data (trainers, sessions, credits).

| Data | Source of truth | MongoDB role |
|---|---|---|
| Customer identity | MongoDB | Primary |
| Card on file | Stripe | Store `stripeCustomerId` only |
| Subscription status | Stripe | Mirror for fast queries |
| Credit balance | MongoDB | Primary (derived from package purchase) |
| Session bookings | MongoDB | Primary |
| Charge history | Stripe | Mirror for reporting |

**Implication:** whenever Stripe state changes, a webhook must sync MongoDB. If the two ever disagree, Stripe wins.

### 7.2 Collections

| Collection | Purpose | Key indexes |
|---|---|---|
| `users` | Identity, role, Stripe customer ID | `email` (unique), `stripeCustomerId` |
| `trainers` | Trainer profiles and availability | `userId` |
| `plans` | Recurring membership plans | `trainerId`, `stripePriceId` |
| `packages` | Prepaid session bundles | `trainerId`, `stripePriceId` |
| `sessions` | Scheduled sessions | `trainerId + startsAt`, `clientId` |
| `bookings` | Booking records tied to sessions | `sessionId`, `clientId` |
| `credits` | Credit ledger per client | `clientId`, `expiresAt` |
| `subscriptions` | Local mirror of Stripe subscriptions | `stripeSubscriptionId`, `clientId` |
| `charges` | Local mirror of charges (for reporting) | `stripeId`, `clientId` |
| `progressLogs` | Workout and measurement logs | `clientId + date` |
| `webhookEvents` | Idempotency store | `eventId` (unique) |

### 7.3 Why MongoDB

- Flexible schema suits a domain with many optional fields (plans, packages, progress logs).
- Mongoose gives typed schemas close to TypeScript's type system.
- Atlas provides managed hosting, backups, and monitoring out of the box.
- Horizontal scaling via sharding is available if needed.

### 7.4 Idempotency

The `webhookEvents` collection stores every processed Stripe `event.id`. Before handling an event, the dispatcher checks whether it has been processed. If yes, it returns `200 OK` immediately. This prevents:

- Double-granting credits
- Double-creating subscriptions
- Duplicate charge records

The check-and-insert should be atomic (unique index on `eventId` + `findOneAndUpdate` with upsert).

---

## 8. Stripe Integration Architecture

### 8.1 Payment method routing

| Flow | Stripe primitive | Why |
|---|---|---|
| Membership subscription | Checkout Session (mode=subscription) | Handles trials, proration, invoices out of the box |
| Package purchase | Checkout Session (mode=payment) | Hosted page, low code, PCI compliant |
| Drop-in session | PaymentIntent + PaymentSheet | Native in-app UI on mobile |
| Tip | PaymentIntent | Custom amount, no product needed |
| No-show fee | PaymentIntent (off_session) | Charge saved card without user present |
| Deposit hold | PaymentIntent with manual capture | Authorize now, capture only if needed |
| Save card for later | SetupIntent | Store PaymentMethod on Customer |
| Manage billing | Billing Portal session | Cancel, update card, view invoices |
| Refunds | Refund API | Partial or full, tracked via webhook |

### 8.2 The universal payment flow

Every payment follows this shape:

```
1. Client → API: "I want to pay for X"
2. API → Stripe: create PaymentIntent / Checkout Session
3. Stripe → API: return client_secret / checkout URL
4. API → Client: return client_secret / checkout URL
5. Client → Stripe: present payment UI, confirm payment
6. Stripe → API: webhook (payment_intent.succeeded, etc.)
7. API: update MongoDB (grant credits, mark booking paid)
8. Client: refetch state → sees updated entitlements
```

**Critical rule:** the client never tells the API "payment succeeded." Only Stripe does that, via webhook.

### 8.3 Off-session charging

For no-show fees and renewals, the API charges the saved card without the user present:

```ts
stripe.paymentIntents.create({
  customer: user.stripeCustomerId,
  payment_method: savedPaymentMethodId,
  amount: feeAmount,
  currency: 'usd',
  confirm: true,
  off_session: true,
}, { idempotencyKey: `no-show-${bookingId}` });
```

`off_session: true` requires the card to have been saved with explicit consent (SetupIntent) and SCA-compliant. If the charge requires authentication, Stripe returns an error and the API must notify the user to re-authenticate.

### 8.4 Why Checkout for subscriptions, PaymentSheet for drop-ins

- **Subscriptions** benefit from Checkout's hosted UI — trial handling, invoice generation, and SCA are all managed by Stripe. Less code, fewer bugs.
- **Drop-ins** benefit from a native, in-app experience. PaymentSheet keeps the user in the app and supports Apple Pay / Google Pay natively.

---

## 9. Authentication & Authorization

### 9.1 Scheme

- **JWT** issued by the API after successful login.
- Access token lifetime: short (e.g., 15 minutes).
- Refresh token: longer (e.g., 30 days), rotated on use.
- Roles: `client`, `trainer`, `admin`.

### 9.2 Storage per client

| Client | Storage | Rationale |
|---|---|---|
| Web | httpOnly cookie (preferred) or memory + refresh | httpOnly prevents XSS token theft |
| Android | EncryptedSharedPreferences | Backed by Android Keystore |
| iOS | Keychain | Hardware-backed, encrypted |

Never use `localStorage` for web tokens or `UserDefaults` for iOS tokens.

### 9.3 Authorization

Role-based access control enforced by middleware:

```ts
router.post('/trainers', requireAuth, requireRole('trainer'), createTrainer);
router.get('/me/subscriptions', requireAuth, getMySubscriptions);
```

Resource-level checks (e.g., "is this trainer allowed to edit this plan?") live in the service layer, not middleware.

### 9.4 Why JWT and not sessions

- Multiple heterogeneous clients (web, native) — JWT travels uniformly.
- Stateless verification scales horizontally without a shared session store.
- Refresh token rotation keeps the security model reasonable.

Trade-off: revocation is harder than with sessions. Mitigated by short access token lifetime and a server-side refresh token blocklist if needed.

---

## 10. API Contract & Versioning

### 10.1 OpenAPI as the source of truth

`openapi/openapi.yaml` defines every endpoint, request, and response. It is the **contract** between the API and all clients.

Generated artifacts:

| Client | Generator | Output |
|---|---|---|
| Web (TS) | `openapi-typescript` or `orval` | `packages/api-client-ts/` |
| Android (Java) | OpenAPI Generator (`java` + `okhttp-gson`) | Retrofit interfaces + DTOs |
| iOS (Swift) | OpenAPI Generator (`swift5`) | Codable models + URLSession client |

**Workflow:** change `openapi.yaml` → regenerate → update clients.

### 10.2 Versioning

All routes are namespaced under `/api/v1`. Breaking changes go to `/api/v2`. Non-breaking changes (new optional fields, new endpoints) are added to the current version.

### 10.3 Why OpenAPI over GraphQL

- Payments are transactional and mutation-heavy — REST fits better.
- Code generation for Java and Swift is mature for OpenAPI.
- A GraphQL layer can be added later on top of the same services if needed.

---

## 11. Error Handling & Resilience

### 11.1 API

- A single `AppError` class carries an HTTP status, a machine-readable code, and a user-safe message.
- A global error middleware catches everything and returns a consistent JSON shape:

```json
{
  "error": {
    "code": "BOOKING_ALREADY_EXISTS",
    "message": "You already have a booking for this session."
  }
}
```

- Unexpected errors are logged with a request ID and returned as `500` without leaking internals.

### 11.2 Stripe calls

- All Stripe calls go through `stripe.service`, which wraps them with:
  - Timeouts
  - Retries on transient network failures (with exponential backoff)
  - Idempotency keys for POST requests
- Stripe errors are mapped to `AppError` subtypes (e.g., `card_declined` → `PAYMENT_DECLINED`).

### 11.3 Webhooks

- Signature verification failure → `400`, logged.
- Handler failure → `500`, so Stripe retries. Handlers must be idempotent.
- Unknown event types → `200`, ignored (Stripe sends new event types over time).

### 11.4 Clients

- Network failures show a retry UI.
- 401 → attempt refresh; if refresh fails, log out.
- 402 (payment required) → route to billing.
- 5xx → generic error, retry option.

---

## 12. Security Architecture

| Concern | Mitigation |
|---|---|
| Secret key exposure | `sk_*` only on server; clients use `pk_*` |
| Card data | Never touches SpotMe servers — Stripe Elements / PaymentSheet only |
| Webhook spoofing | Signature verification with `whsec_*` |
| JWT theft | Short access tokens, httpOnly cookies on web, Keychain/Keystore on mobile |
| Brute force | Rate limiting on `/auth/*` |
| Injection | Zod validation on all inputs; Mongoose parameterized queries |
| PII leakage | Structured logs redact card data, tokens, and full emails |
| Replay | Idempotency keys on Stripe requests; `webhookEvents` collection |
| CSRF (web) | SameSite cookies + CSRF token if using cookies |
| Man-in-the-middle | HTTPS everywhere; certificate pinning optional on mobile |

### 12.1 PCI scope

By using Stripe Checkout, Stripe Elements, and PaymentSheet, SpotMe never touches raw card data. This keeps the platform in **PCI SAQ A** scope — the lightest compliance burden.

---

## 13. Observability

| Signal | Tool |
|---|---|
| Structured logs | pino → stdout → log aggregator |
| Request tracing | Request ID middleware, propagated to logs |
| Metrics | Prometheus-compatible endpoint (post-v1) |
| Errors | Sentry (or similar) on API and clients |
| Stripe events | Stripe Dashboard + Workbench |
| Uptime | External health check on `/healthz` |

### 13.1 What to log

- Request ID, method, path, status, duration
- User ID (when authenticated)
- Stripe event ID (in webhook handlers)
- Errors with stack traces (server-side only)

### 13.2 What NOT to log

- Card numbers, CVCs, or full PANs
- JWTs or refresh tokens
- Full request bodies on payment endpoints
- Stripe secret keys

---

## 14. Scalability & Performance

### 14.1 Current scale assumptions

- Low thousands of users
- Hundreds of concurrent requests
- Stripe API calls are the bottleneck for write-heavy flows

### 14.2 Scaling levers

| Layer | Lever |
|---|---|
| API | Horizontal scaling behind a load balancer; stateless JWT |
| MongoDB | Atlas replica set; read replicas for analytics |
| Web | Static hosting + CDN |
| Webhooks | Idempotency prevents duplicate work under retries |
| Stripe | Idempotency keys prevent duplicate charges |
| Caching | Redis for session refresh blocklist and rate limits (post-v1) |

### 14.3 Performance notes

- Always paginate list endpoints (`/trainers`, `/me/bookings`).
- Index the fields used in filters and sorts.
- Avoid N+1 queries by populating Mongoose references explicitly.
- Cache trainer profiles on the client for short TTLs.

---

## 15. Deployment Topology

```
                    ┌─────────────────┐
                    │   Cloudflare    │
                    │   (CDN + WAF)   │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
      ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
      │  React Web  │ │  Express    │ │  Static     │
      │  (Vercel)   │ │  API        │ │  Assets     │
      │             │ │  (Render /  │ │  (S3 / R2)  │
      │             │ │  Fly / ECS) │ │             │
      └─────────────┘ └──────┬──────┘ └─────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  MongoDB Atlas  │
                    │  (M10+)         │
                    └─────────────────┘

      ┌─────────────┐        ┌─────────────┐
      │  Google Play│        │  App Store  │
      │  (Android)  │        │  (iOS)      │
      └─────────────┘        └─────────────┘
```

| Component | Hosting |
|---|---|
| React Web | Vercel / Netlify / Cloudflare Pages |
| Express API | Render / Railway / Fly.io / AWS ECS |
| MongoDB | MongoDB Atlas |
| Webhooks | Stripe → public API URL |
| Push | FCM (Android), APNs (iOS) |
| Secrets | Host env vars; local `.env` never committed |

---

## 16. Key Design Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Layered monolith backend | Coupling between payments, bookings, and credits makes microservices premature |
| 2 | Services hold all business logic | Controllers stay thin; services testable without HTTP |
| 3 | Stripe as source of truth for payment state | MongoDB mirrors; webhooks drive sync |
| 4 | Webhook idempotency via `webhookEvents` | Stripe retries; duplicates must not double-charge |
| 5 | Checkout for subscriptions, PaymentSheet for drop-ins | Best UX per flow; less code for complex flows |
| 6 | JWT auth | Uniform across web, Android, iOS; stateless |
| 7 | OpenAPI as contract | Prevents drift between three clients |
| 8 | MongoDB | Flexible schema fits evolving domain; Mongoose gives typed models |
| 9 | MVVM on mobile, feature-sliced on web | Matches each platform's idioms |
| 10 | All Stripe calls funnel through `stripe.service` | Single place to add retries, idempotency, logging |

---

## 17. Trade-offs & Alternatives Considered

| Alternative | Why not chosen |
|---|---|
| Microservices | Premature; distributed transactions across payments + bookings would be a liability |
| GraphQL | Payments are mutation-heavy; OpenAPI codegen for Java/Swift is more mature |
| Sessions instead of JWT | Multi-client uniformity and stateless scaling favored JWT |
| PostgreSQL instead of MongoDB | Domain has evolving, nested data; Mongo's flexible schema fits better. Postgres is a valid alternative if strict relational integrity is preferred |
| Flutter / React Native instead of native | Native was chosen for full access to platform APIs (HealthKit, Google Fit, widgets) |
| Server-side rendering for web | Not needed for an authenticated dashboard-heavy app; client-side rendering is simpler |
| Kafka / event bus for webhooks | Overkill at this scale; a single API with idempotent handlers suffices |
| Storing card data locally | Never — PCI scope would explode; Stripe holds cards |

---

## 18. Future Evolution

Planned or plausible extensions, in rough order of likelihood:

1. **Stripe Connect** — turn SpotMe into a marketplace where trainers onboard as connected accounts and the platform takes a commission.
2. **Usage-based billing** — metered billing for virtual sessions or API-driven workout plans.
3. **Real-time updates** — WebSockets or SSE for session reminders and payment confirmations without polling.
4. **Analytics pipeline** — export events to a warehouse (BigQuery, Snowflake) for trainer dashboards.
5. **Waitlist auto-charge** — offer open spots to the next client with a payment window; requires careful idempotency.
6. **Multi-region deployment** — only if latency or compliance demands it.
7. **Service extraction** — pull billing or notifications into a separate service if scale or team structure warrants it.
8. **Admin tooling** — internal dashboard for refunds, disputes, and manual credit adjustments.

Each of these can be added without breaking the existing architecture because:

- Services are already isolated from transport (REST today, anything tomorrow).
- Clients already consume a versioned contract.
- Stripe is already wrapped behind a single module.

---

## Appendix A — Folder-to-Responsibility Map

| Folder | Owns | Must not |
|---|---|---|
| `apps/api/src/routes` | URL → controller mapping | Contain business logic |
| `apps/api/src/controllers` | Request parsing, response shaping | Touch Mongoose or Stripe directly |
| `apps/api/src/services` | Business rules, orchestration | Import from `express` |
| `apps/api/src/models` | Schema, indexes, data access | Contain business rules |
| `apps/api/src/webhooks` | Event handling, idempotency | Call Express-specific APIs |
| `apps/api/src/middleware` | Cross-cutting HTTP concerns | Contain domain logic |
| `apps/web/src/features` | Feature UI, hooks, state | Call Stripe directly |
| `apps/android/.../data` | Network, cache, repositories | Contain UI logic |
| `apps/android/.../ui` | Activities, Fragments, ViewModels | Call Stripe SDK outside `stripe/` package |
| `apps/ios/SpotMe/Core` | Networking, storage, Stripe wrapper | Contain feature UI |
| `apps/ios/SpotMe/Features` | Feature views and view models | Reach into URLSession directly |

---

## Appendix B — Sequence: Subscription Purchase

```
Client           API              Stripe            MongoDB
  │               │                 │                  │
  │ POST /checkout/subscription      │                  │
  ├──────────────►│                 │                  │
  │               │ create Checkout Session            │
  │               ├────────────────►│                  │
  │               │                 │                  │
  │               │◄────────────────┤ { url, id }      │
  │               │                 │                  │
  │◄──────────────┤ { url }         │                  │
  │               │                 │                  │
  │ open URL      │                 │                  │
  ├───────────────────────────────► │                  │
  │               │                 │                  │
  │ user pays     │                 │                  │
  │               │                 │                  │
  │               │◄────────────────┤ checkout.session │
  │               │                 │  .completed      │
  │               │ verify + idempotency              │
  │               ├───────────────────────────────────►│
  │               │                 │                  │ insert Subscription
  │               │                 │                  │
  │               │◄────────────────┤ customer.sub     │
  │               │                 │  scription.created│
  │               ├───────────────────────────────────►│
  │               │                 │                  │ update currentPeriodEnd
  │               │                 │                  │
  │ refetch /me/subscriptions       │                  │
  ├──────────────►│                 │                  │
  │               ├───────────────────────────────────►│
  │◄──────────────┤ active plan     │                  │
```

---

## Appendix C — Glossary

| Term | Meaning |
|---|---|
| **PaymentIntent** | Stripe object representing a single payment attempt |
| **SetupIntent** | Stripe object for saving a card without charging |
| **Checkout Session** | Stripe-hosted payment page |
| **PaymentSheet** | Native in-app payment UI (Android/iOS) |
| **Billing Portal** | Stripe-hosted UI for customers to manage billing |
| **Off-session** | Charging a saved card without the customer present |
| **Idempotency key** | A unique key that ensures a request is executed at most once |
| **Webhook** | Server-to-server event notification from Stripe |
| **Entitlement** | A user's right to access a feature (e.g., active subscription, available credits) |

---

<p align="center">
  <em>Architecture is the set of decisions that are expensive to change. Everything here is intentional.</em>
</p>
