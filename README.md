# SpotMe

> **SpotMe** — a fitness coaching platform where clients subscribe to trainers for ongoing sessions, book one-time drop-ins, or buy prepaid session packages. Built with a polyglot stack: **React + TypeScript** (web), **Express + TypeScript** (API), **Java** (Android), **Swift** (iOS), **MongoDB**, and **Stripe**.

---

## Table of Contents

- [What SpotMe Is For](#what-spotme-is-for)
- [Tech Stack](#tech-stack)
- [High-Level Architecture](#high-level-architecture)
- [Monorepo Structure](#monorepo-structure)
- [Backend — Express + TypeScript](#backend--express--typescript)
- [Web — React + TypeScript](#web--react--typescript)
- [Android — Java](#android--java)
- [iOS — Swift](#ios--swift)
- [Shared Contract — OpenAPI](#shared-contract--openapi)
- [Data Models](#data-models)
- [API Routes](#api-routes)
- [Payment Flows](#payment-flows)
- [Webhook Events](#webhook-events)
- [Cross-Cutting Concerns](#cross-cutting-concerns)
- [Local Development Setup](#local-development-setup)
- [Environment Variables](#environment-variables)
- [Deployment Overview](#deployment-overview)
- [Build Roadmap](#build-roadmap)
- [Testing](#testing)
- [Summary](#summary)
- [License](#license)

---

## What SpotMe Is For

**SpotMe** is a fitness coaching platform where:

- **Trainers** offer monthly memberships, session packages, and drop-in sessions.
- **Clients** subscribe, buy credits, book sessions, and manage billing.
- **Stripe** handles every payment type: subscriptions, one-time charges, off-session no-show fees, deposits, and refunds.

**Why this architecture exists:**

- One backend = one place for all Stripe secret logic and business rules.
- Multiple clients (web, Android, iOS) = the product is accessible everywhere.
- Native Android/iOS = full access to platform features (push notifications, HealthKit/Google Fit, background services, widgets).
- React web = fast iteration, admin dashboards, and a fallback for users who don't want to install the app.

---

## Tech Stack

| Layer | Technology | Language |
|---|---|---|
| Web | React, React Router, Vite, Tailwind CSS | TypeScript |
| Backend | Node.js, Express, Zod, Mongoose | TypeScript |
| Android | Native Android, Retrofit, Room, WorkManager | Java |
| iOS | SwiftUI, URLSession, SwiftData, Keychain | Swift |
| Database | MongoDB (Atlas) | — |
| Payments | Stripe Checkout, PaymentIntents, Billing, SetupIntents, Customer Portal | — |
| Contract | OpenAPI 3.0 | YAML |
| Auth | JWT | — |
| Dev Tools | Stripe CLI, Postman, MongoDB Compass, Docker | — |

---

## High-Level Architecture

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
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐       │
│  │  Auth       │  │  Trainers   │  │  Bookings   │  │  Checkout   │       │
│  │  Routes     │  │  Routes     │  │  Routes     │  │  Routes     │       │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘       │
│         │                │                │                │              │
│  ┌──────▼────────────────▼────────────────▼────────────────▼───────┐      │
│  │                    SERVICE LAYER (business logic)               │      │
│  │  authService  trainerService  bookingService  stripeService     │      │
│  │  creditsService  subscriptionService  notificationService       │      │
│  └──────┬────────────────┬────────────────┬────────────────┬───────┘      │
│         │                │                │                │              │
│  ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐       │
│  │  Mongoose   │  │  Stripe     │  │   Webhook   │  │    JWT      │       │
│  │  Models     │  │  SDK        │  │   Handler   │  │  Middleware │       │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────────────┘       │
│         │                │                │                               │
└─────────┼────────────────┼────────────────┼───────────────────────────────┘
          │                │                │
          ▼                ▼                ▼
   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
   │  MongoDB    │  │   Stripe    │  │  Stripe     │
   │  (Atlas)    │  │   API       │  │  Webhooks   │
   └─────────────┘  └─────────────┘  └─────────────┘
```

**Key rules:**

- Only the Express API holds `STRIPE_SECRET_KEY`.
- Clients only receive `client_secret` values and use publishable keys.
- Webhooks are the **only** trusted source for payment confirmation.

---

## Monorepo Structure

```
spotme/
├── README.md
├── .gitignore
├── docker-compose.yml              → local MongoDB + Redis (optional)
│
├── openapi/
│   └── openapi.yaml                → single source of truth for the API contract
│
├── apps/
│   ├── api/                        → Express + TypeScript
│   ├── web/                        → React + TypeScript
│   ├── android/                    → Java (Gradle)
│   └── ios/                        → Swift (Xcode)
│
├── packages/
│   ├── api-client-ts/              → generated TS client (used by web)
│   ├── shared-types/               → shared TS types (web ↔ api only)
│   └── config/                     → shared ESLint, tsconfig, prettier
│
└── docs/
    ├── architecture.md
    ├── stripe-flows.md
    └── api-reference.md
```

> **Note:** TypeScript packages are shared between `api` and `web` only. Android and iOS consume the API via generated clients from `openapi/openapi.yaml`.

---

## Backend — Express + TypeScript

### Purpose

The single source of truth for business logic, data, and Stripe integration. Every client talks to it. It owns:

- Authentication and authorization
- Data persistence (MongoDB)
- Stripe API calls (PaymentIntents, Subscriptions, Checkout, Customers, SetupIntents, Refunds)
- Webhook processing
- Business rules (credits, cancellations, no-show fees, entitlements)

### Folder Structure

```
apps/api/
├── package.json
├── tsconfig.json
├── .env.example
├── Dockerfile
│
├── src/
│   ├── server.ts                   → starts HTTP server
│   ├── app.ts                      → Express app, middleware, route mounting
│   │
│   ├── config/
│   │   ├── env.ts                  → Zod-validated environment variables
│   │   ├── db.ts                   → MongoDB connection (Mongoose)
│   │   └── stripe.ts               → Stripe client instance
│   │
│   ├── models/                     → Mongoose schemas
│   │   ├── User.model.ts
│   │   ├── Trainer.model.ts
│   │   ├── Plan.model.ts
│   │   ├── Package.model.ts
│   │   ├── Session.model.ts
│   │   ├── Booking.model.ts
│   │   ├── Credit.model.ts
│   │   ├── Subscription.model.ts
│   │   ├── Charge.model.ts
│   │   ├── ProgressLog.model.ts
│   │   └── WebhookEvent.model.ts   → idempotency store
│   │
│   ├── routes/
│   │   ├── index.ts                → mounts all routers under /api/v1
│   │   ├── auth.routes.ts
│   │   ├── trainers.routes.ts
│   │   ├── plans.routes.ts
│   │   ├── packages.routes.ts
│   │   ├── checkout.routes.ts
│   │   ├── sessions.routes.ts
│   │   ├── bookings.routes.ts
│   │   ├── billing.routes.ts
│   │   ├── me.routes.ts
│   │   └── webhooks.routes.ts      → /api/v1/webhooks/stripe
│   │
│   ├── controllers/                → parse request → call service → send response
│   │   ├── auth.controller.ts
│   │   ├── trainer.controller.ts
│   │   ├── checkout.controller.ts
│   │   ├── session.controller.ts
│   │   ├── billing.controller.ts
│   │   └── webhook.controller.ts
│   │
│   ├── services/                   → business logic (no Express types here)
│   │   ├── auth.service.ts
│   │   ├── trainer.service.ts
│   │   ├── plan.service.ts
│   │   ├── package.service.ts
│   │   ├── credits.service.ts
│   │   ├── booking.service.ts
│   │   ├── subscription.service.ts
│   │   ├── stripe.service.ts       → wraps Stripe SDK calls
│   │   ├── webhook.service.ts      → dispatches by event type
│   │   └── notification.service.ts → email/push (later)
│   │
│   ├── middleware/
│   │   ├── auth.ts                 → verifies JWT
│   │   ├── role.ts                 → requires 'trainer' | 'client' | 'admin'
│   │   ├── validate.ts             → Zod request validation
│   │   ├── errorHandler.ts         → centralized error responses
│   │   ├── notFound.ts             → 404 handler
│   │   └── rateLimit.ts            → express-rate-limit
│   │
│   ├── webhooks/
│   │   ├── index.ts                → event router
│   │   ├── handleCheckoutCompleted.ts
│   │   ├── handleSubscriptionCreated.ts
│   │   ├── handleSubscriptionUpdated.ts
│   │   ├── handleSubscriptionDeleted.ts
│   │   ├── handleInvoicePaid.ts
│   │   ├── handleInvoiceFailed.ts
│   │   ├── handlePaymentIntentSucceeded.ts
│   │   ├── handleChargeRefunded.ts
│   │   └── handleSetupIntentSucceeded.ts
│   │
│   ├── schemas/                    → Zod schemas (request validation)
│   │   ├── auth.schema.ts
│   │   ├── booking.schema.ts
│   │   └── checkout.schema.ts
│   │
│   ├── lib/
│   │   ├── AppError.ts             → custom error class
│   │   ├── logger.ts               → pino / winston
│   │   ├── jwt.ts                  → sign/verify helpers
│   │   └── stripeEvents.ts         → event type constants
│   │
│   ├── types/
│   │   ├── express.d.ts            → extends Request with `user`
│   │   └── index.ts
│   │
│   └── utils/
│       ├── asyncHandler.ts
│       ├── date.ts
│       └── money.ts                → cents ↔ display
│
├── tests/
│   ├── unit/
│   ├── integration/
│   └── fixtures/
│
└── scripts/
    ├── seed.ts                     → seed demo data
    └── sync-stripe-prices.ts       → create Products/Prices in Stripe
```

### Why This Structure

- **Routes → Controllers → Services → Models** keeps concerns separated. Controllers stay thin; services hold logic and are unit-testable without Express.
- **Webhooks are isolated** so each event handler is small, testable, and easy to reason about.
- **`WebhookEvent` model** stores `event.id` to make handlers **idempotent** — Stripe retries events, and you must not double-grant credits.

---

## Web — React + TypeScript

### Purpose

- Public marketing site + trainer directory
- Client portal (book, manage subscription, view credits)
- Trainer dashboard (manage plans, availability, earnings)
- Admin dashboard (view payments, disputes, refunds)

### Folder Structure

```
apps/web/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
│
├── public/
│   └── assets/
│
└── src/
    ├── main.tsx
    ├── App.tsx                     → route tree
    │
    ├── app/
    │   ├── store.ts                → Redux Toolkit or Zustand store
    │   ├── router.tsx              → React Router config
    │   └── providers.tsx           → QueryClient, Stripe, Auth providers
    │
    ├── api/
    │   ├── client.ts               → axios instance with JWT interceptor
    │   ├── auth.api.ts
    │   ├── trainers.api.ts
    │   ├── checkout.api.ts
    │   ├── sessions.api.ts
    │   └── billing.api.ts
    │
    ├── features/                   → feature-sliced organization
    │   ├── auth/
    │   │   ├── components/
    │   │   ├── hooks/
    │   │   ├── pages/
    │   │   └── authSlice.ts
    │   ├── trainers/
    │   ├── booking/
    │   ├── subscription/
    │   ├── credits/
    │   ├── checkout/
    │   └── progress/
    │
    ├── components/                 → shared UI
    │   ├── ui/                     → Button, Input, Modal, Card
    │   ├── layout/                 → Navbar, Sidebar, Footer
    │   └── stripe/                 → StripeElementsWrapper, PaymentForm
    │
    ├── pages/                      → top-level route pages
    │   ├── Home.tsx
    │   ├── Login.tsx
    │   ├── Register.tsx
    │   ├── TrainerProfile.tsx
    │   ├── Checkout.tsx
    │   ├── Success.tsx
    │   ├── Dashboard/
    │   │   ├── ClientDashboard.tsx
    │   │   ├── TrainerDashboard.tsx
    │   │   └── AdminDashboard.tsx
    │   └── NotFound.tsx
    │
    ├── hooks/
    │   ├── useAuth.ts
    │   ├── useSubscription.ts
    │   ├── useCredits.ts
    │   └── useStripeCheckout.ts
    │
    ├── lib/
    │   ├── stripe.ts               → loadStripe(publishableKey)
    │   ├── format.ts               → currency, dates
    │   └── validation.ts
    │
    ├── types/
    │   └── index.ts                → re-exports from shared-types
    │
    └── styles/
        └── index.css               → Tailwind base
```

### Why This Structure

- **Feature-sliced** keeps everything for a feature (components, hooks, pages, state) together. Scales better than a flat `components/` + `pages/` layout.
- **`api/` layer** centralizes HTTP calls — you can swap axios for a generated OpenAPI client later without touching features.
- **Stripe Elements** is isolated in `components/stripe/` so the payment UI is reusable.

---

## Android — Java

### Purpose

Native Android app for clients (and a lightweight trainer view). Full access to Google Fit, push notifications, background session reminders, and Google Pay.

### Architecture Pattern

**MVVM (Model–View–ViewModel)** with **Repository** pattern. This is the standard modern Android architecture.

- **View** (Activity/Fragment) — observes ViewModel state, renders UI
- **ViewModel** — holds UI state, survives rotation, calls Repository
- **Repository** — single source of truth; decides between network and local cache
- **Retrofit** for API calls, **Room** for local cache, **DataStore** for tokens

### Folder Structure

```
apps/android/
├── build.gradle
├── settings.gradle
├── gradle.properties
│
└── app/
    ├── build.gradle
    ├── google-services.json          → Firebase (for push)
    │
    └── src/main/
        ├── AndroidManifest.xml
        │
        ├── java/com/spotme/app/
        │   ├── SpotMeApplication.java
        │   │
        │   ├── data/
        │   │   ├── api/
        │   │   │   ├── ApiService.java           → Retrofit interface
        │   │   │   ├── RetrofitClient.java       → OkHttp + interceptors
        │   │   │   ├── AuthInterceptor.java      → adds JWT
        │   │   │   └── dto/                      → request/response models
        │   │   │       ├── LoginRequest.java
        │   │   │       ├── LoginResponse.java
        │   │   │       ├── TrainerDto.java
        │   │   │       ├── BookingDto.java
        │   │   │       ├── CheckoutSessionDto.java
        │   │   │       └── ...
        │   │   ├── local/
        │   │   │   ├── AppDatabase.java          → Room
        │   │   │   ├── dao/
        │   │   │   └── entity/
        │   │   ├── repository/
        │   │   │   ├── AuthRepository.java
        │   │   │   ├── TrainerRepository.java
        │   │   │   ├── BookingRepository.java
        │   │   │   ├── CheckoutRepository.java
        │   │   │   └── SubscriptionRepository.java
        │   │   └── prefs/
        │   │       └── TokenStore.java           → EncryptedSharedPreferences
        │   │
        │   ├── domain/
        │   │   ├── model/                        → domain models (not DTOs)
        │   │   │   ├── User.java
        │   │   │   ├── Trainer.java
        │   │   │   ├── Session.java
        │   │   │   └── Subscription.java
        │   │   └── usecase/
        │   │       ├── LoginUseCase.java
        │   │       ├── BookSessionUseCase.java
        │   │       └── StartCheckoutUseCase.java
        │   │
        │   ├── ui/
        │   │   ├── auth/
        │   │   │   ├── LoginActivity.java
        │   │   │   ├── RegisterActivity.java
        │   │   │   └── AuthViewModel.java
        │   │   ├── trainers/
        │   │   │   ├── TrainerListFragment.java
        │   │   │   ├── TrainerDetailFragment.java
        │   │   │   └── TrainerViewModel.java
        │   │   ├── booking/
        │   │   │   ├── CalendarFragment.java
        │   │   │   ├── BookingConfirmFragment.java
        │   │   │   └── BookingViewModel.java
        │   │   ├── checkout/
        │   │   │   ├── PaymentSheetFragment.java → wraps Stripe PaymentSheet
        │   │   │   └── CheckoutViewModel.java
        │   │   ├── subscription/
        │   │   │   ├── MySubscriptionFragment.java
        │   │   │   └── SubscriptionViewModel.java
        │   │   ├── progress/
        │   │   │   ├── ProgressFragment.java
        │   │   │   └── ProgressViewModel.java
        │   │   └── common/
        │   │       ├── BaseActivity.java
        │   │       ├── BaseFragment.java
        │   │       └── LoadingDialog.java
        │   │
        │   ├── stripe/
        │   │   ├── StripePaymentManager.java     → wraps Stripe SDK
        │   │   └── PaymentResultHandler.java
        │   │
        │   ├── notifications/
        │   │   ├── SessionReminderWorker.java    → WorkManager
        │   │   └── PushService.java              → FCM
        │   │
        │   └── util/
        │       ├── Constants.java
        │       ├── DateUtils.java
        │       └── Result.java                   → success/error wrapper
        │
        └── res/
            ├── layout/
            ├── values/
            ├── drawable/
            └── navigation/
```

### Why This Structure

- **Data / Domain / UI** layering is the modern Android standard. It keeps Stripe and network code out of UI classes.
- **DTOs separate from domain models** — API JSON shapes change; your app models shouldn't.
- **`stripe/` package wraps the Stripe SDK** so payment logic is centralized and testable.
- **WorkManager** handles background session reminders, and **Room** caches trainer/session data for offline viewing.

### Stripe in Android (Java)

1. Client calls `POST /api/v1/checkout/drop-in` on your Express API.
2. API creates a PaymentIntent and returns `client_secret`.
3. Android initializes `PaymentSheet` with the `client_secret` and publishable key.
4. User pays; the sheet confirms the PaymentIntent directly with Stripe.
5. Your Express webhook receives `payment_intent.succeeded` and updates MongoDB.
6. Android refetches booking state on the next screen.

---

## iOS — Swift

### Purpose

Native iOS app for clients and trainers. Full access to Apple Pay, HealthKit, Face ID, push notifications, and iOS widgets.

### Architecture Pattern

**MVVM + Repository** with **SwiftUI** (or UIKit if you prefer). Use **async/await** for networking and **Combine** for reactive state where useful.

- **View** (SwiftUI) — declarative UI, observes ViewModel
- **ViewModel** — `@Observable` or `ObservableObject`, holds state, calls Repository
- **Repository** — abstracts network + cache
- **URLSession** for networking, **Keychain** for tokens, **SwiftData** or **Core Data** for cache

### Folder Structure

```
apps/ios/
├── SpotMe.xcodeproj
├── Podfile                         → if using CocoaPods (or SPM)
│
└── SpotMe/
    ├── App/
    │   ├── SpotMeApp.swift          → @main entry
    │   ├── AppState.swift           → global auth/session state
    │   └── AppRouter.swift          → navigation
    │
    ├── Core/
    │   ├── Network/
    │   │   ├── APIClient.swift       → URLSession wrapper
    │   │   ├── APIEndpoint.swift     → endpoint definitions
    │   │   ├── APIError.swift
    │   │   └── AuthInterceptor.swift → attaches JWT
    │   ├── Storage/
    │   │   ├── KeychainStore.swift   → secure token storage
    │   │   └── CacheStore.swift      → SwiftData / Core Data
    │   ├── Stripe/
    │   │   ├── StripeService.swift   → wraps Stripe SDK
    │   │   └── PaymentSheetPresenter.swift
    │   └── Extensions/
    │       ├── Date+Format.swift
    │       └── Decimal+Currency.swift
    │
    ├── Data/
    │   ├── DTO/
    │   │   ├── LoginRequest.swift
    │   │   ├── LoginResponse.swift
    │   │   ├── TrainerDTO.swift
    │   │   ├── BookingDTO.swift
    │   │   ├── CheckoutSessionDTO.swift
    │   │   └── ...
    │   ├── Repositories/
    │   │   ├── AuthRepository.swift
    │   │   ├── TrainerRepository.swift
    │   │   ├── BookingRepository.swift
    │   │   ├── CheckoutRepository.swift
    │   │   └── SubscriptionRepository.swift
    │   └── Models/
    │       ├── User.swift
    │       ├── Trainer.swift
    │       ├── Session.swift
    │       └── Subscription.swift
    │
    ├── Features/
    │   ├── Auth/
    │   │   ├── LoginView.swift
    │   │   ├── RegisterView.swift
    │   │   └── AuthViewModel.swift
    │   ├── Trainers/
    │   │   ├── TrainersListView.swift
    │   │   ├── TrainerDetailView.swift
    │   │   └── TrainersViewModel.swift
    │   ├── Booking/
    │   │   ├── CalendarView.swift
    │   │   ├── BookingConfirmView.swift
    │   │   └── BookingViewModel.swift
    │   ├── Checkout/
    │   │   ├── PaymentSheetView.swift
    │   │   └── CheckoutViewModel.swift
    │   ├── Subscription/
    │   │   ├── MySubscriptionView.swift
    │   │   └── SubscriptionViewModel.swift
    │   ├── Progress/
    │   │   ├── ProgressView.swift
    │   │   └── ProgressViewModel.swift
    │   └── Dashboard/
    │       └── TrainerDashboardView.swift
    │
    ├── Shared/
    │   ├── Components/
    │   │   ├── PrimaryButton.swift
    │   │   ├── CardView.swift
    │   │   └── LoadingView.swift
    │   └── Theme/
    │       ├── Colors.swift
    │       └── Typography.swift
    │
    ├── Notifications/
    │   ├── PushManager.swift
    │   └── LocalReminderScheduler.swift
    │
    ├── Health/
    │   └── HealthKitManager.swift   → optional: sync workouts
    │
    └── Resources/
        ├── Assets.xcassets
        ├── Info.plist
        └── Localizable.strings
```

### Why This Structure

- **Features/** mirrors Android's package layout — easier to keep the two native apps conceptually in sync.
- **Core/Network** isolates URLSession and JWT handling.
- **Core/Stripe** wraps the Stripe iOS SDK so payment presentation is one call from the ViewModel.
- **Keychain** stores the JWT — never `UserDefaults` for auth tokens.

### Stripe in iOS (Swift)

1. Client calls `POST /api/v1/checkout/drop-in` on your Express API.
2. API creates a PaymentIntent and returns `client_secret`.
3. `StripeService` presents `PaymentSheet` with the `client_secret` and publishable key.
4. User pays with Apple Pay or card; Stripe confirms the PaymentIntent.
5. Your Express webhook updates MongoDB.
6. iOS refetches state.

---

## Shared Contract — OpenAPI

### Purpose

One YAML file defines every endpoint, request body, and response. From it you generate typed clients for all platforms, so the API contract stays in sync.

```
openapi/openapi.yaml
```

Example snippet:

```yaml
openapi: 3.0.3
info:
  title: SpotMe API
  version: 1.0.0
servers:
  - url: https://api.spotme.app/api/v1
paths:
  /checkout/drop-in:
    post:
      summary: Create a PaymentIntent for a drop-in session
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [sessionId]
              properties:
                sessionId: { type: string }
      responses:
        '200':
          description: PaymentIntent client secret
          content:
            application/json:
              schema:
                type: object
                properties:
                  clientSecret: { type: string }
```

### Generation Targets

| Target | Tool | Output |
|---|---|---|
| Web (TS) | `openapi-typescript` or `orval` | `packages/api-client-ts/` |
| Android (Java) | OpenAPI Generator (`java` + `okhttp-gson`) | Retrofit interfaces + models |
| iOS (Swift) | OpenAPI Generator (`swift5`) | Codable models + URLSession client |

**Workflow:** change `openapi.yaml` → run generator → all three clients get updated types. This is how you avoid drift between platforms.

---

## Data Models

```js
// Users
{
  _id, email, name, passwordHash,
  role: 'trainer' | 'client' | 'admin',
  stripeCustomerId
}

// Trainers
{
  _id, userId, bio, specialties: [String],
  rates, availability: [{ day, start, end }]
}

// Plans (recurring memberships)
{
  _id, trainerId, name, stripePriceId,
  interval: 'month', sessionsPerMonth, amount
}

// Packages (prepaid session bundles)
{
  _id, trainerId, name, stripePriceId,
  sessionCount, expiresAfterDays, amount
}

// Sessions
{
  _id, trainerId, clientId, startsAt,
  status: 'scheduled' | 'completed' | 'cancelled' | 'no-show',
  priceType: 'membership' | 'drop-in' | 'package'
}

// Bookings
{
  _id, sessionId, clientId,
  paymentIntentId, status
}

// Credits
{
  _id, clientId, source: 'package',
  remaining, expiresAt
}

// Subscriptions
{
  _id, clientId, planId,
  stripeSubscriptionId, status, currentPeriodEnd
}

// Charges
{
  _id, clientId,
  type: 'drop-in' | 'no-show' | 'tip' | 'package',
  amount, stripeId, status
}

// ProgressLogs
{
  _id, clientId, date, metrics, notes
}

// WebhookEvents (idempotency)
{
  _id, eventId, type, processedAt
}
```

---

## API Routes

### Auth
```
POST   /api/v1/auth/register
POST   /api/v1/auth/login
GET    /api/v1/auth/me
```

### Trainers
```
GET    /api/v1/trainers
GET    /api/v1/trainers/:id
POST   /api/v1/trainers                    (trainer only)
PUT    /api/v1/trainers/:id                (trainer only)
```

### Plans & Packages
```
POST   /api/v1/trainers/:id/plans          (trainer only)
POST   /api/v1/trainers/:id/packages       (trainer only)
GET    /api/v1/trainers/:id/plans
GET    /api/v1/trainers/:id/packages
```

### Checkout & Billing
```
POST   /api/v1/checkout/subscription       → Checkout (subscription mode)
POST   /api/v1/checkout/drop-in            → PaymentIntent
POST   /api/v1/checkout/package            → Checkout (payment mode)
POST   /api/v1/checkout/tip                → PaymentIntent
POST   /api/v1/billing-portal              → Customer Portal session
```

### Sessions & Bookings
```
POST   /api/v1/sessions                    → book a session
PUT    /api/v1/sessions/:id/reschedule
DELETE /api/v1/sessions/:id                → cancel (policy applied)
POST   /api/v1/sessions/:id/no-show        → off-session charge
```

### Client
```
GET    /api/v1/me/subscriptions
GET    /api/v1/me/credits
GET    /api/v1/me/bookings
GET    /api/v1/me/progress
```

### Webhooks
```
POST   /api/v1/webhooks/stripe
```

---

## Payment Flows

### A. Client subscribes to a trainer (recurring)

```
Client (any)  →  POST /api/v1/checkout/subscription
              →  Express creates Stripe Checkout Session (mode=subscription)
              →  Returns { url }
Client        →  Opens Checkout URL in browser / webview / native sheet
User          →  Pays
Stripe        →  POST /api/v1/webhooks/stripe  (checkout.session.completed)
Express       →  Creates local Subscription record
              →  POST /api/v1/webhooks/stripe  (customer.subscription.created)
              →  Syncs currentPeriodEnd
Client        →  Refetches /me/subscriptions → sees active plan
```

### B. Client books a drop-in session (one-time)

```
Client        →  POST /api/v1/checkout/drop-in  { sessionId }
Express       →  Creates PaymentIntent
              →  Returns { clientSecret }
Client        →  Presents native PaymentSheet with clientSecret
User          →  Pays
Stripe        →  POST /api/v1/webhooks/stripe  (payment_intent.succeeded)
Express       →  Marks Booking confirmed, decrements credits if applicable
Client        →  Refetches /me/bookings → sees confirmed session
```

### C. No-show off-session charge

```
SetupIntent at signup  →  Card saved to Stripe Customer
Client cancels late    →  DELETE /api/v1/sessions/:id
Express               →  Policy check: <24h → charge fee
                      →  Creates off_session PaymentIntent with saved PaymentMethod
Stripe                →  Charges card, fires payment_intent.succeeded
Express               →  Records no-show Charge, notifies client
```

### D. Package purchase + credit ledger

```
Client        →  POST /api/v1/checkout/package  { packageId }
Express       →  Stripe Checkout (mode=payment)
Stripe        →  checkout.session.completed webhook
Express       →  Creates Credit { remaining: 10, expiresAt: +90d }
Client        →  Books session → Express decrements Credit.remaining
```

---

## Webhook Events

Handle these events in `/api/v1/webhooks/stripe`:

| Event | Action |
|---|---|
| `checkout.session.completed` | Fulfill one-time purchase or activate subscription |
| `customer.subscription.created` | Create local subscription record |
| `customer.subscription.updated` | Sync plan changes, pauses, proration |
| `customer.subscription.deleted` | Mark subscription cancelled |
| `invoice.payment_succeeded` | Extend `currentPeriodEnd`, grant monthly credits |
| `invoice.payment_failed` | Flag subscription as past due, notify client |
| `payment_intent.succeeded` | Grant access to drop-in or package |
| `charge.refunded` | Revoke access, update purchase record |
| `setup_intent.succeeded` | Save card for future off-session charges |

> **Important:** Always verify the webhook signature using `stripe.webhooks.constructEvent()` and use the raw request body — never `express.json()` on the webhook route.

```ts
// Express + TypeScript webhook handler (simplified)
app.post(
  '/api/v1/webhooks/stripe',
  express.raw({ type: 'application/json' }),
  (req: Request, res: Response) => {
    const sig = req.headers['stripe-signature'] as string;
    const event = stripe.webhooks.constructEvent(
      req.body as Buffer,
      sig,
      env.STRIPE_WEBHOOK_SECRET
    );

    switch (event.type) {
      case 'checkout.session.completed':
        // Fulfill one-time purchase OR activate subscription
        break;
      case 'customer.subscription.created':
      case 'customer.subscription.updated':
      case 'customer.subscription.deleted':
        // Sync subscription state to MongoDB
        break;
      case 'invoice.payment_succeeded':
      case 'invoice.payment_failed':
        // Update subscription status, notify user
        break;
      case 'payment_intent.succeeded':
        // Grant access to pay-per-view post or product
        break;
      case 'charge.refunded':
        // Revoke access, update purchase record
        break;
    }

    res.json({ received: true });
  }
);
```

---

## Cross-Cutting Concerns

| Concern | Where it lives |
|---|---|
| Auth | JWT issued by Express; stored in Keychain (iOS), EncryptedSharedPreferences (Android), httpOnly cookie or memory + refresh (web) |
| Error handling | Global error middleware in Express; typed `Result` / `ApiError` on clients |
| Validation | Zod on Express; client-side validation is UX only — server is authoritative |
| Idempotency | `WebhookEvent` collection stores `event.id`; Stripe requests use `Idempotency-Key` header |
| Logging | pino on Express; structured logs on clients |
| Rate limiting | `express-rate-limit` on auth + checkout routes |
| Secrets | `.env` on server; never in clients. Clients only get publishable keys |
| Versioning | All routes under `/api/v1` |
| Time zones | Store UTC everywhere; convert on clients |

---

## Local Development Setup

### Prerequisites

- Node.js 18+
- MongoDB (local or Atlas)
- Stripe account (free)
- Stripe CLI
- Android Studio (for Java app)
- Xcode (for iOS app, macOS only)
- Docker (optional, for local MongoDB)

### Running Locally

```bash
# Terminal 1 — MongoDB
docker compose up -d mongo

# Terminal 2 — API
cd apps/api && npm run dev

# Terminal 3 — Web
cd apps/web && npm run dev

# Terminal 4 — Stripe webhook forwarding
stripe listen --forward-to localhost:5000/api/v1/webhooks/stripe

# Terminal 5 — Android
cd apps/android && ./gradlew installDebug

# Terminal 6 — iOS
cd apps/ios && xcodebuild -scheme SpotMe -destination 'platform=iOS Simulator,name=iPhone 15'
```

---

## Environment Variables

Create a `.env` file in `apps/api`:

```env
PORT=5000
MONGO_URI=mongodb://localhost:27017/spotme
JWT_SECRET=your_jwt_secret_min_32_chars

STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxxx
STRIPE_WEBHOOK_SECRET=whsec_xxxxxxxxxxxxx
STRIPE_PRICE_MEMBERSHIP=price_xxxxxxxxxxxxx
STRIPE_PRICE_DROPIN=price_xxxxxxxxxxxxx

CLIENT_URL=http://localhost:5173
```

Create a `.env` file in `apps/web`:

```env
VITE_API_URL=http://localhost:5000/api/v1
VITE_STRIPE_PUBLISHABLE_KEY=pk_test_xxxxxxxxxxxxx
```

For Android and iOS, store only the **publishable key** and API base URL in config files — never the secret key.

---

## Deployment Overview

| Component | Hosting |
|---|---|
| Express API | Render, Railway, Fly.io, or AWS ECS |
| MongoDB | MongoDB Atlas |
| React Web | Vercel, Netlify, or Cloudflare Pages |
| Android | Google Play Console |
| iOS | Apple App Store Connect |
| Webhooks | Stripe → your public API URL (`/api/v1/webhooks/stripe`) |
| Push notifications | Firebase Cloud Messaging (both platforms) |
| Secrets | Host env vars + `.env` locally (never committed) |

---

## Build Roadmap

- [ ] **Phase 1** — Auth + roles (trainer vs client) on Express
- [ ] **Phase 2** — Trainer profile + plan/package creation (Stripe Products & Prices)
- [ ] **Phase 3** — Drop-in booking with one-time PaymentIntent
- [ ] **Phase 4** — Membership checkout (subscription mode) + webhook sync
- [ ] **Phase 5** — Credit ledger for packages
- [ ] **Phase 6** — Booking calendar with availability
- [ ] **Phase 7** — Cancellation policy + no-show off-session charge
- [ ] **Phase 8** — Customer Portal for billing management
- [ ] **Phase 9** — React web client for all flows
- [ ] **Phase 10** — Android (Java) native client
- [ ] **Phase 11** — iOS (Swift) native client
- [ ] **Phase 12** — Progress tracking + subscriber-only analytics
- [ ] **Phase 13** — Stripe Connect for multi-trainer payouts (stretch)

---

## Testing

Use Stripe's official test cards in Test Mode:

| Scenario | Card Number |
|---|---|
| Successful payment | `4242 4242 4242 4242` |
| Requires 3D Secure | `4000 0025 0000 3155` |
| Declined | `4000 0000 0000 9995` |
| Insufficient funds | `4000 0000 0000 9995` |
| Expired card | `4000 0000 0000 0069` |

Use any future expiry date, any 3-digit CVC, and any postal code.

**Stripe Test Clocks** — simulate time passing to test renewals, trial expirations, and package expiry without waiting.

```bash
stripe trigger checkout.session.completed
stripe trigger invoice.payment_failed
stripe trigger customer.subscription.deleted
stripe trigger customer.subscription.trial_will_end
```

---

## Summary

| Layer | Purpose | Owns |
|---|---|---|
| **Express + TS API** | Single source of truth | Business logic, MongoDB, Stripe secrets, webhooks |
| **React + TS Web** | Fast, accessible client; admin + trainer dashboards | UI, Stripe Elements, JWT storage |
| **Java Android** | Native Android experience | Native UI, Google Pay, FCM push, Google Fit |
| **Swift iOS** | Native iOS experience | Native UI, Apple Pay, APNs push, HealthKit |
| **OpenAPI contract** | Cross-platform agreement | Endpoint shapes, request/response types |
| **MongoDB** | Persistence | Users, subscriptions, credits, bookings |
| **Stripe** | Money movement | Payments, subscriptions, refunds, webhooks |

The backend is the brain. The clients are faces. Stripe is the wallet. OpenAPI is the translator. That's the whole system.

---

## License

MIT — free to use, modify, and learn from.

---

<p align="center">
  Built to learn Stripe the hard way — one webhook at a time.
</p>