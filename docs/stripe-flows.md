# SpotMe — Stripe Flows

> Detailed documentation of every payment flow in SpotMe. Each flow includes the trigger, the sequence of calls, the Stripe primitives used, the webhook events involved, and the expected state changes in MongoDB.

For the API contract, see [openapi/openapi.yaml](../openapi/openapi.yaml). For setup, see [README.md](./README.md).

---

## Table of Contents

- [1. Principles](#1-principles)
- [2. Flow Index](#2-flow-index)
- [3. Membership Subscription](#3-membership-subscription)
- [4. Drop-In Session](#4-drop-in-session)
- [5. Session Package Purchase](#5-session-package-purchase)
- [6. Tip a Trainer](#6-tip-a-trainer)
- [7. Save Card for Later (SetupIntent)](#7-save-card-for-later-setupintent)
- [8. No-Show Fee (Off-Session Charge)](#8-no-show-fee-off-session-charge)
- [9. Booking Deposit (Manual Capture)](#9-booking-deposit-manual-capture)
- [10. Membership Pause](#10-membership-pause)
- [11. Upgrade / Downgrade Plan](#11-upgrade--downgrade-plan)
- [12. Cancel Subscription](#12-cancel-subscription)
- [13. Refund a Purchase](#13-refund-a-purchase)
- [14. Failed Payment Recovery](#14-failed-payment-recovery)
- [15. Billing Portal](#15-billing-portal)
- [16. Webhook Handling Rules](#16-webhook-handling-rules)
- [17. Test Cards & Simulation](#17-test-cards--simulation)
- [18. Common Pitfalls](#18-common-pitfalls)

---

## 1. Principles

Every Stripe flow in SpotMe follows these rules:

1. **The API creates Stripe objects. Clients never do.** Clients only receive `client_secret` values and use publishable keys.
2. **Payment state is confirmed by webhooks, not by client claims.** A client saying "payment succeeded" is not trusted.
3. **All POST requests to Stripe use an idempotency key.** This prevents duplicate charges on retries.
4. **Every webhook handler is idempotent.** Stripe retries; the `webhookEvents` collection guards against duplicates.
5. **MongoDB is a projection of Stripe state.** If the two disagree, Stripe is correct and MongoDB must be re-synced.
6. **Entitlements are derived, not stored on the client.** A user's access to a feature is computed from their subscription and credits, both stored server-side.

---

## 2. Flow Index

| # | Flow | Stripe Primitive | Client Entry Point |
|---|---|---|---|
| 3 | Membership subscription | Checkout Session (subscription) | `POST /api/v1/checkout/subscription` |
| 4 | Drop-in session | PaymentIntent + PaymentSheet | `POST /api/v1/checkout/drop-in` |
| 5 | Session package | Checkout Session (payment) | `POST /api/v1/checkout/package` |
| 6 | Tip | PaymentIntent | `POST /api/v1/checkout/tip` |
| 7 | Save card | SetupIntent | `POST /api/v1/billing/setup-intent` |
| 8 | No-show fee | PaymentIntent (off_session) | `POST /api/v1/sessions/:id/no-show` |
| 9 | Booking deposit | PaymentIntent (manual capture) | `POST /api/v1/sessions/:id/deposit` |
| 10 | Pause membership | Subscription `pause_collection` | `POST /api/v1/billing/pause` |
| 11 | Plan change | Subscription update with proration | `POST /api/v1/billing/change-plan` |
| 12 | Cancel subscription | Subscription update/cancel | `POST /api/v1/billing/cancel` |
| 13 | Refund | Refund API | `POST /api/v1/admin/refunds` |
| 14 | Failed payment | Invoice webhooks | (automatic) |
| 15 | Billing portal | Billing Portal session | `POST /api/v1/billing-portal` |

---

## 3. Membership Subscription

**Use case:** A client subscribes to a trainer's monthly plan (e.g., $150/mo for 4 sessions).

**Why Checkout Session:** Handles trials, proration, invoices, SCA, and card updates out of the box. Less code, fewer bugs.

### 3.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /checkout/subscription            │                    │
  │ { planId }       │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ validate plan       │                    │
  │                  │ ensure customer     │                    │
  │                  ├────────────────────►│                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ customerId         │
  │                  │                     │                    │
  │                  │ create Checkout Session (mode=subscription)
  │                  ├────────────────────►│                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ { url, id }        │
  │                  │                     │                    │
  │◄─────────────────┤ { url }             │                    │
  │                  │                     │                    │
  │ open Checkout URL│                     │                    │
  ├───────────────────────────────────────►│                    │
  │                  │                     │                    │
  │ user pays        │                     │                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ checkout.session   │
  │                  │                     │  .completed        │
  │                  │ verify signature    │                    │
  │                  │ idempotency check   │                    │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ insert Subscription
  │                  │                     │                    │
  │                  │◄────────────────────┤ customer.sub       │
  │                  │                     │  scription.created │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ update currentPeriodEnd
  │                  │                     │                    │
  │                  │◄────────────────────┤ invoice            │
  │                  │                     │  .payment_succeeded│
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ grant monthly credits
  │                  │                     │                    │
  │ refetch /me/subscriptions              │                    │
  ├─────────────────►│                     │                    │
  │                  ├─────────────────────────────────────────►│
  │◄─────────────────┤ active plan         │                    │
```

### 3.2 API handler (simplified)

```ts
// POST /api/v1/checkout/subscription
export const createSubscriptionCheckout = async (req, res) => {
  const { planId } = req.body;
  const plan = await planService.getById(planId);
  const customer = await stripeService.ensureCustomer(req.user);

  const session = await stripe.checkout.sessions.create({
    mode: 'subscription',
    customer: customer.id,
    line_items: [{ price: plan.stripePriceId, quantity: 1 }],
    success_url: `${env.CLIENT_URL}/success?session_id={CHECKOUT_SESSION_ID}`,
    cancel_url: `${env.CLIENT_URL}/trainers/${plan.trainerId}`,
    subscription_data: {
      metadata: {
        planId: plan._id.toString(),
        clientId: req.user._id.toString(),
        trainerId: plan.trainerId.toString(),
      },
    },
    metadata: {
      planId: plan._id.toString(),
      clientId: req.user._id.toString(),
    },
  }, { idempotencyKey: `sub-checkout-${req.user._id}-${planId}` });

  res.json({ url: session.url });
};
```

### 3.3 Webhook handler

```ts
// webhooks/handleCheckoutCompleted.ts
export async function handleCheckoutCompleted(event: Stripe.Event) {
  const session = event.data.object as Stripe.Checkout.Session;

  if (session.mode === 'subscription') {
    const sub = await stripe.subscriptions.retrieve(
      session.subscription as string
    );
    await subscriptionService.upsertFromStripe(sub);
    return;
  }

  if (session.mode === 'payment') {
    // One-time purchase — see package flow
    await purchaseService.fulfillFromCheckout(session);
    return;
  }
}
```

### 3.4 State changes

| Object | Change |
|---|---|
| `users` | `stripeCustomerId` set if new |
| `subscriptions` | Inserted with `stripeSubscriptionId`, `planId`, `status: 'active'`, `currentPeriodEnd` |
| `credits` | Granted monthly allotment (if plan includes sessions) |

### 3.5 Events handled

- `checkout.session.completed`
- `customer.subscription.created`
- `customer.subscription.updated`
- `invoice.payment_succeeded`

---

## 4. Drop-In Session

**Use case:** A client books a single session and pays $40.

**Why PaymentIntent + PaymentSheet:** Native in-app UI, supports Apple Pay and Google Pay, keeps the user in the app.

### 4.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /checkout/drop-in                 │                    │
  │ { sessionId }    │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ check session available
  │                  │ check user entitlement
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │
  │                  │ create PaymentIntent│                    │
  │                  │ { amount, metadata }│                    │
  │                  ├────────────────────►│                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ client_secret      │
  │                  │                     │                    │
  │                  │ store pending booking                    │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │
  │◄─────────────────┤ { clientSecret }    │                    │
  │                  │                     │                    │
  │ present PaymentSheet                   │                    │
  ├───────────────────────────────────────►│                    │
  │                  │                     │                    │
  │ user pays        │                     │                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ payment_intent     │
  │                  │                     │  .succeeded        │
  │                  │ verify signature    │                    │
  │                  │ idempotency check   │                    │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ mark booking paid
  │                  │                     │                    │ decrement credit (if any)
  │                  │                     │                    │
  │ refetch /me/bookings                   │                    │
  ├─────────────────►│                     │                    │
  │◄─────────────────┤ confirmed booking   │                    │
```

### 4.2 API handler

```ts
// POST /api/v1/checkout/drop-in
export const createDropIn = async (req, res) => {
  const { sessionId } = req.body;

  const session = await sessionService.getAvailableById(sessionId);
  if (!session) throw new AppError(409, 'SESSION_UNAVAILABLE');

  // If user has credits, use them instead of charging
  const credit = await creditsService.findUsable(req.user._id, session.trainerId);
  if (credit) {
    const booking = await bookingService.createFromCredit(req.user, session, credit);
    return res.json({ booking, requiresPayment: false });
  }

  const paymentIntent = await stripe.paymentIntents.create({
    amount: session.priceCents,
    currency: 'usd',
    customer: req.user.stripeCustomerId,
    metadata: {
      type: 'drop-in',
      sessionId,
      clientId: req.user._id.toString(),
      trainerId: session.trainerId.toString(),
    },
    automatic_payment_methods: { enabled: true },
  }, { idempotencyKey: `drop-in-${req.user._id}-${sessionId}` });

  await bookingService.createPending(req.user, session, paymentIntent.id);

  res.json({ clientSecret: paymentIntent.client_secret });
};
```

### 4.3 Webhook handler

```ts
// webhooks/handlePaymentIntentSucceeded.ts
export async function handlePaymentIntentSucceeded(event: Stripe.Event) {
  const pi = event.data.object as Stripe.PaymentIntent;
  const { type } = pi.metadata;

  switch (type) {
    case 'drop-in':
      await bookingService.markPaid(pi.metadata.sessionId, pi.id);
      break;
    case 'tip':
      await tipService.record(pi.metadata.trainerId, pi.amount, pi.id);
      break;
    case 'package':
      // handled via checkout.session.completed, not here
      break;
    case 'no-show-fee':
      await chargeService.recordNoShow(pi.metadata.bookingId, pi.id);
      break;
  }
}
```

### 4.4 State changes

| Object | Change |
|---|---|
| `bookings` | Created with `paymentIntentId`, `status: 'pending'` → `'paid'` after webhook |
| `sessions` | Marked as booked |
| `credits` | Decremented if credit was used |

### 4.5 Events handled

- `payment_intent.succeeded`
- `payment_intent.payment_failed`

---

## 5. Session Package Purchase

**Use case:** A client buys a 10-session package for $350.

**Why Checkout Session:** One-time payment, hosted page, low code. The package becomes a **credit balance** in MongoDB.

### 5.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /checkout/package                 │                    │
  │ { packageId }    │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ create Checkout Session (mode=payment)
  │                  ├────────────────────►│                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ { url, id }        │
  │◄─────────────────┤ { url }             │                    │
  │                  │                     │                    │
  │ open Checkout URL│                     │                    │
  ├───────────────────────────────────────►│                    │
  │ user pays        │                     │                    │
  │                  │◄────────────────────┤ checkout.session   │
  │                  │                     │  .completed        │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ insert Credit
  │                  │                     │                    │ { remaining: 10,
  │                  │                     │                    │   expiresAt: +90d }
  │                  │                     │                    │
  │ refetch /me/credits                    │                    │
  ├─────────────────►│                     │                    │
  │◄─────────────────┤ { remaining: 10 }   │                    │
```

### 5.2 API handler

```ts
// POST /api/v1/checkout/package
export const createPackageCheckout = async (req, res) => {
  const { packageId } = req.body;
  const pkg = await packageService.getById(packageId);
  const customer = await stripeService.ensureCustomer(req.user);

  const session = await stripe.checkout.sessions.create({
    mode: 'payment',
    customer: customer.id,
    line_items: [{ price: pkg.stripePriceId, quantity: 1 }],
    success_url: `${env.CLIENT_URL}/success?session_id={CHECKOUT_SESSION_ID}`,
    cancel_url: `${env.CLIENT_URL}/trainers/${pkg.trainerId}`,
    payment_intent_data: {
      metadata: {
        type: 'package',
        packageId: pkg._id.toString(),
        clientId: req.user._id.toString(),
        trainerId: pkg.trainerId.toString(),
      },
    },
    metadata: {
      type: 'package',
      packageId: pkg._id.toString(),
      clientId: req.user._id.toString(),
    },
  }, { idempotencyKey: `pkg-checkout-${req.user._id}-${packageId}` });

  res.json({ url: session.url });
};
```

### 5.3 Webhook handler

```ts
// webhooks/handleCheckoutCompleted.ts (package branch)
if (session.mode === 'payment' && session.metadata?.type === 'package') {
  const pkg = await packageService.getById(session.metadata.packageId);
  await creditsService.grant({
    clientId: session.metadata.clientId,
    source: 'package',
    sourceId: pkg._id,
    remaining: pkg.sessionCount,
    expiresAt: addDays(new Date(), pkg.expiresAfterDays),
  });
}
```

### 5.4 State changes

| Object | Change |
|---|---|
| `credits` | Inserted with `remaining`, `expiresAt`, `source: 'package'` |
| `charges` | Recorded for reporting |

### 5.5 Events handled

- `checkout.session.completed`

---

## 6. Tip a Trainer

**Use case:** After a session, a client tips their trainer $10.

**Why PaymentIntent:** Custom amount, no product needed.

### 6.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /checkout/tip                     │                    │
  │ { trainerId, amountCents }             │                    │
  ├─────────────────►│                     │                    │
  │                  │ create PaymentIntent│                    │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ client_secret      │
  │◄─────────────────┤ { clientSecret }    │                    │
  │                  │                     │                    │
  │ present PaymentSheet                   │                    │
  ├───────────────────────────────────────►│                    │
  │ user pays        │                     │                    │
  │                  │◄────────────────────┤ payment_intent     │
  │                  │                     │  .succeeded        │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ insert Charge
  │                  │                     │                    │ { type: 'tip' }
```

### 6.2 State changes

| Object | Change |
|---|---|
| `charges` | Inserted with `type: 'tip'`, `amount`, `stripeId` |

### 6.3 Events handled

- `payment_intent.succeeded`

---

## 7. Save Card for Later (SetupIntent)

**Use case:** Save a client's card so it can be charged off-session later (no-show fees, renewals).

**Why SetupIntent:** Saves a PaymentMethod to the Customer without charging. Required for off-session charges and SCA compliance.

### 7.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /billing/setup-intent             │                    │
  ├─────────────────►│                     │                    │
  │                  │ ensure customer     │                    │
  │                  │ create SetupIntent  │                    │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ client_secret      │
  │◄─────────────────┤ { clientSecret }    │                    │
  │                  │                     │                    │
  │ present PaymentSheet (setup mode)      │                    │
  ├───────────────────────────────────────►│                    │
  │ user saves card  │                     │                    │
  │                  │◄────────────────────┤ setup_intent       │
  │                  │                     │  .succeeded        │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ store paymentMethodId
  │                  │                     │                    │ on user
```

### 7.2 API handler

```ts
// POST /api/v1/billing/setup-intent
export const createSetupIntent = async (req, res) => {
  const customer = await stripeService.ensureCustomer(req.user);

  const setupIntent = await stripe.setupIntents.create({
    customer: customer.id,
    payment_method_types: ['card'],
    usage: 'off_session',
    metadata: { clientId: req.user._id.toString() },
  });

  res.json({ clientSecret: setupIntent.client_secret });
};
```

### 7.3 Webhook handler

```ts
// webhooks/handleSetupIntentSucceeded.ts
export async function handleSetupIntentSucceeded(event: Stripe.Event) {
  const si = event.data.object as Stripe.SetupIntent;
  await userService.setDefaultPaymentMethod(
    si.metadata!.clientId,
    si.payment_method as string
  );
}
```

### 7.4 State changes

| Object | Change |
|---|---|
| `users` | `defaultPaymentMethodId` set |

### 7.5 Events handled

- `setup_intent.succeeded`

---

## 8. No-Show Fee (Off-Session Charge)

**Use case:** A client cancels less than 24 hours before a session. A $25 fee is charged to their saved card.

**Why off-session PaymentIntent:** The user is not present. Requires `off_session: true` and a saved PaymentMethod with explicit consent (via SetupIntent).

### 8.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ DELETE /sessions/:id                   │                    │
  ├─────────────────►│                     │                    │
  │                  │ check cancellation policy
  │                  │ (< 24h → fee applies)│                   │
  │                  │                     │                    │
  │                  │ create PaymentIntent│                    │
  │                  │ { off_session: true,│                    │
  │                  │   confirm: true }   │                    │
  │                  ├────────────────────►│                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ succeeded OR       │
  │                  │                     │ requires_action    │
  │                  │                     │                    │
  │                  │ if requires_action: │                    │
  │                  │ return client_secret│                    │
  │                  │ for client to authenticate                │
  │                  │                     │                    │
  │◄─────────────────┤ { status, clientSecret? }                │
  │                  │                     │                    │
  │                  │◄────────────────────┤ payment_intent     │
  │                  │                     │  .succeeded        │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ insert Charge
  │                  │                     │                    │ { type: 'no-show' }
```

### 8.2 API handler

```ts
// POST /api/v1/sessions/:id/no-show
export const chargeNoShowFee = async (req, res) => {
  const booking = await bookingService.getById(req.params.id);
  const trainer = await trainerService.getById(booking.trainerId);

  if (!trainer.noShowFeeCents) {
    return res.json({ charged: false, reason: 'NO_FEE_CONFIGURED' });
  }

  const user = await userService.getById(booking.clientId);
  if (!user.defaultPaymentMethodId) {
    return res.json({ charged: false, reason: 'NO_PAYMENT_METHOD' });
  }

  try {
    const pi = await stripe.paymentIntents.create({
      customer: user.stripeCustomerId,
      payment_method: user.defaultPaymentMethodId,
      amount: trainer.noShowFeeCents,
      currency: 'usd',
      confirm: true,
      off_session: true,
      metadata: {
        type: 'no-show-fee',
        bookingId: booking._id.toString(),
        clientId: user._id.toString(),
        trainerId: trainer._id.toString(),
      },
    }, { idempotencyKey: `no-show-${booking._id}` });

    await chargeService.recordNoShow(booking._id, pi.id);
    await bookingService.markNoShow(booking._id);

    res.json({ charged: true, paymentIntentId: pi.id });
  } catch (err) {
    if (err.code === 'authentication_required') {
      // Card requires 3DS — client must authenticate
      const pi = err.raw.payment_intent;
      return res.json({
        charged: false,
        requiresAction: true,
        clientSecret: pi.client_secret,
      });
    }
    throw err;
  }
};
```

### 8.3 State changes

| Object | Change |
|---|---|
| `bookings` | `status: 'no-show'` |
| `charges` | Inserted with `type: 'no-show'` |

### 8.4 Events handled

- `payment_intent.succeeded`
- `payment_intent.payment_failed`
- `payment_intent.requires_action` (client must authenticate)

### 8.5 Important notes

- **Consent is required.** The card must have been saved via SetupIntent with `usage: 'off_session'`.
- **SCA may interrupt.** If the card requires 3DS, Stripe returns `authentication_required`. The client must present the `client_secret` to complete authentication.
- **Idempotency key must include the booking ID** so retries don't double-charge.

---

## 9. Booking Deposit (Manual Capture)

**Use case:** A client books a session and authorizes a $20 deposit. The deposit is captured only if they no-show or cancel late.

**Why manual capture:** Authorize now, capture later. If the client shows up, the authorization is canceled (or released after 7 days automatically).

### 9.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /sessions/:id/deposit             │                    │
  ├─────────────────►│                     │                    │
  │                  │ create PaymentIntent│                    │
  │                  │ { capture_method: 'manual' }             │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ client_secret      │
  │◄─────────────────┤ { clientSecret }    │                    │
  │                  │                     │                    │
  │ present PaymentSheet                   │                    │
  ├───────────────────────────────────────►│                    │
  │ user authorizes  │                     │                    │
  │                  │◄────────────────────┤ payment_intent     │
  │                  │                     │  .requires_capture │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ store deposit
  │                  │                     │                    │ intentId
  │                  │                     │                    │
  │ user shows up    │                     │                    │
  │                  │ POST /sessions/:id/deposit/capture       │
  │                  ├────────────────────►│                    │
  │                  │                     │ cancel PI instead  │
  │                  │◄────────────────────┤ (release hold)     │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ mark released
```

### 9.2 API handler (create)

```ts
// POST /api/v1/sessions/:id/deposit
export const createDeposit = async (req, res) => {
  const booking = await bookingService.getById(req.params.id);
  const trainer = await trainerService.getById(booking.trainerId);

  const pi = await stripe.paymentIntents.create({
    customer: req.user.stripeCustomerId,
    amount: trainer.depositCents,
    currency: 'usd',
    capture_method: 'manual',
    metadata: {
      type: 'deposit',
      bookingId: booking._id.toString(),
    },
  }, { idempotencyKey: `deposit-${booking._id}` });

  await bookingService.storeDepositIntent(booking._id, pi.id);

  res.json({ clientSecret: pi.client_secret });
};
```

### 9.3 API handler (capture on no-show)

```ts
// POST /api/v1/sessions/:id/deposit/capture
export const captureDeposit = async (req, res) => {
  const booking = await bookingService.getById(req.params.id);
  const pi = await stripe.paymentIntents.capture(booking.depositIntentId, {
    amount_to_capture: booking.depositCents,
  }, { idempotencyKey: `deposit-capture-${booking._id}` });

  await chargeService.recordDepositCapture(booking._id, pi.id);
  res.json({ captured: true });
};
```

### 9.4 State changes

| Object | Change |
|---|---|
| `bookings` | `depositIntentId` set; later `depositStatus: 'released' | 'captured'` |
| `charges` | Recorded only if captured |

### 9.5 Important notes

- Authorizations expire after **7 days** by default (varies by card network). If the session is scheduled beyond that window, use a SetupIntent + off-session charge instead.
- `capture_method: 'manual'` does **not** support all payment methods (e.g., not for some wallets).
- Always cancel the PaymentIntent if the client shows up — do not leave authorizations hanging.

---

## 10. Membership Pause

**Use case:** A client pauses their membership for a 2-week vacation.

**Why `pause_collection`:** Pauses billing without canceling the subscription. Keeps the same subscription ID and history.

### 10.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /billing/pause                    │                    │
  │ { resumeAt }     │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ subscription.update │                    │
  │                  │ { pause_collection: │                    │
  │                  │   { behavior: 'void',│                   │
  │                  │     resumes_at } }  │                    │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ updated sub        │
  │                  │                     │                    │
  │                  │ sync local record   │                    │
  │                  ├─────────────────────────────────────────►│
  │◄─────────────────┤ { status: 'paused' }│                    │
```

### 10.2 API handler

```ts
// POST /api/v1/billing/pause
export const pauseSubscription = async (req, res) => {
  const { resumeAt } = req.body; // ISO date
  const sub = await subscriptionService.getActiveForUser(req.user._id);

  const updated = await stripe.subscriptions.update(
    sub.stripeSubscriptionId,
    {
      pause_collection: {
        behavior: 'void',
        resumes_at: Math.floor(new Date(resumeAt).getTime() / 1000),
      },
    },
    { idempotencyKey: `pause-${sub._id}-${resumeAt}` }
  );

  await subscriptionService.upsertFromStripe(updated);
  res.json({ status: updated.status });
};
```

### 10.3 State changes

| Object | Change |
|---|---|
| `subscriptions` | `pauseCollection: { behavior, resumesAt }` mirrored locally |

### 10.4 Events handled

- `customer.subscription.updated`

### 10.5 Important notes

- `behavior: 'void'` skips invoices during the pause.
- `behavior: 'keep_as_draft'` creates draft invoices you can review later.
- Pausing is reversible — the same subscription resumes.

---

## 11. Upgrade / Downgrade Plan

**Use case:** A client switches from Basic ($50/mo) to Pro ($100/mo).

**Why Subscription update with proration:** Stripe computes the prorated difference and issues a credit or charge on the next invoice.

### 11.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /billing/change-plan              │                    │
  │ { newPlanId }    │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ subscription.update │                    │
  │                  │ { items: [{ id,     │                    │
  │                  │   price: newPrice }],│                   │
  │                  │   proration_behavior:│                   │
  │                  │   'create_prorations' }                  │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ updated sub        │
  │                  │                     │                    │
  │                  │ sync local record   │                    │
  │                  ├─────────────────────────────────────────►│
  │◄─────────────────┤ { status: 'active' }│                    │
```

### 11.2 API handler

```ts
// POST /api/v1/billing/change-plan
export const changePlan = async (req, res) => {
  const { newPlanId } = req.body;
  const sub = await subscriptionService.getActiveForUser(req.user._id);
  const newPlan = await planService.getById(newPlanId);

  const updated = await stripe.subscriptions.update(
    sub.stripeSubscriptionId,
    {
      items: [{
        id: sub.stripeItemId,
        price: newPlan.stripePriceId,
      }],
      proration_behavior: 'create_prorations',
      metadata: { planId: newPlanId },
    },
    { idempotencyKey: `change-plan-${sub._id}-${newPlanId}` }
  );

  await subscriptionService.upsertFromStripe(updated);
  res.json({ status: updated.status });
};
```

### 11.3 Proration behaviors

| Behavior | Effect |
|---|---|
| `create_prorations` (default) | Credits/charges applied to next invoice |
| `always_invoice` | Charges/credits immediately |
| `none` | No proration — new price starts next cycle |

### 11.4 State changes

| Object | Change |
|---|---|
| `subscriptions` | `planId` updated, `currentPeriodEnd` may change |
| `credits` | Adjusted if plan includes different session counts |

### 11.5 Events handled

- `customer.subscription.updated`
- `invoice.created` (if proration creates an invoice)
- `invoice.payment_succeeded`

---

## 12. Cancel Subscription

**Use case:** A client cancels their membership.

**Why Subscription cancel:** Two options — cancel immediately or at period end.

### 12.1 Sequence

```
Client              API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /billing/cancel                   │                    │
  │ { immediate }    │                     │                    │
  ├─────────────────►│                     │                    │
  │                  │ if immediate:       │                    │
  │                  │   subscription.cancel                    │
  │                  │ else:               │                    │
  │                  │   subscription.update                    │
  │                  │   { cancel_at_period_end: true }         │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ updated sub        │
  │                  │                     │                    │
  │                  │ sync local record   │                    │
  │                  ├─────────────────────────────────────────►│
  │◄─────────────────┤ { status }          │                    │
  │                  │                     │                    │
  │ (later)          │◄────────────────────┤ customer.sub       │
  │                  │                     │  scription.deleted │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ mark cancelled
```

### 12.2 API handler

```ts
// POST /api/v1/billing/cancel
export const cancelSubscription = async (req, res) => {
  const { immediate } = req.body;
  const sub = await subscriptionService.getActiveForUser(req.user._id);

  let updated;
  if (immediate) {
    updated = await stripe.subscriptions.cancel(sub.stripeSubscriptionId);
  } else {
    updated = await stripe.subscriptions.update(sub.stripeSubscriptionId, {
      cancel_at_period_end: true,
    });
  }

  await subscriptionService.upsertFromStripe(updated);
  res.json({ status: updated.status, cancelAtPeriodEnd: updated.cancel_at_period_end });
};
```

### 12.3 State changes

| Object | Change |
|---|---|
| `subscriptions` | `status: 'canceled'` or `cancelAtPeriodEnd: true` |
| `credits` | Remaining credits typically stay valid until expiry |

### 12.4 Events handled

- `customer.subscription.updated`
- `customer.subscription.deleted`

---

## 13. Refund a Purchase

**Use case:** An admin issues a refund for a drop-in session or package.

**Why Refund API:** Partial or full refunds, tracked via webhook.

### 13.1 Sequence

```
Admin               API                  Stripe              MongoDB
  │                  │                     │                    │
  │ POST /admin/refunds                    │                    │
  │ { paymentIntentId, amountCents? }      │                    │
  ├─────────────────►│                     │                    │
  │                  │ refund.create       │                    │
  │                  ├────────────────────►│                    │
  │                  │◄────────────────────┤ refund object      │
  │                  │                     │                    │
  │◄─────────────────┤ { refundId }        │                    │
  │                  │                     │                    │
  │                  │◄────────────────────┤ charge.refunded    │
  │                  ├─────────────────────────────────────────►│
  │                  │                     │                    │ revoke entitlement
  │                  │                     │                    │ (if applicable)
```

### 13.2 API handler

```ts
// POST /api/v1/admin/refunds
export const createRefund = async (req, res) => {
  const { paymentIntentId, amountCents } = req.body;

  const refund = await stripe.refunds.create({
    payment_intent: paymentIntentId,
    amount: amountCents, // omit for full refund
    reason: 'requested_by_customer',
  }, { idempotencyKey: `refund-${paymentIntentId}-${amountCents ?? 'full'}` });

  res.json({ refundId: refund.id, status: refund.status });
};
```

### 13.3 Webhook handler

```ts
// webhooks/handleChargeRefunded.ts
export async function handleChargeRefunded(event: Stripe.Event) {
  const charge = event.data.object as Stripe.Charge;
  const refund = charge.refunds?.data[0];

  // Find local charge record
  const localCharge = await chargeService.findByStripeId(charge.payment_intent as string);
  if (!localCharge) return;

  await chargeService.markRefunded(localCharge._id, refund?.id, refund?.amount);

  // Revoke entitlements if fully refunded
  if (charge.refunded) {
    await creditsService.revokeBySource(localCharge._id);
    await bookingService.revokeByPayment(localCharge.stripeId);
  }
}
```

### 13.4 State changes

| Object | Change |
|---|---|
| `charges` | `status: 'refunded'`, `refundId`, `refundedAmount` |
| `credits` | Revoked if source charge was fully refunded |
| `bookings` | Canceled if source charge was refunded |

### 13.5 Events handled

- `charge.refunded`
- `refund.created`
- `refund.updated`
- `refund.failed`

---

## 14. Failed Payment Recovery

**Use case:** A subscription renewal fails (expired card, insufficient funds).

**Why invoice webhooks:** Stripe retries failed invoices based on your dashboard settings. Your job is to mirror state and notify the user.

### 14.1 Sequence

```
                    API                  Stripe              MongoDB
                     │                     │                    │
                     │◄────────────────────┤ invoice            │
                     │                     │  .payment_failed   │
                     │ verify + idempotency│                    │
                     ├─────────────────────────────────────────►│
                     │                     │                    │ mark past_due
                     │                     │                    │
                     │                     │                    │ notify user
                     │                     │                    │ (email/push)
                     │                     │                    │
                     │◄────────────────────┤ invoice            │
                     │                     │  .payment_succeeded│
                     │                     │  (after retry)     │
                     ├─────────────────────────────────────────►│
                     │                     │                    │ mark active
                     │                     │                    │
                     │◄────────────────────┤ customer.sub       │
                     │                     │  scription.deleted │
                     │                     │  (if all retries   │
                     │                     │   fail)            │
                     ├─────────────────────────────────────────►│
                     │                     │                    │ mark canceled
```

### 14.2 Webhook handler

```ts
// webhooks/handleInvoiceFailed.ts
export async function handleInvoiceFailed(event: Stripe.Event) {
  const invoice = event.data.object as Stripe.Invoice;

  await subscriptionService.markPastDue(
    invoice.subscription as string,
    invoice.attempt_count
  );

  await notificationService.sendPaymentFailed(
    invoice.customer as string,
    invoice.hosted_invoice_url
  );
}
```

### 14.3 State changes

| Object | Change |
|---|---|
| `subscriptions` | `status: 'past_due'`, `failedAttempts` incremented |
| `users` | Notified via email/push |

### 14.4 Events handled

- `invoice.payment_failed`
- `invoice.payment_succeeded` (recovery)
- `customer.subscription.deleted` (final failure)

### 14.5 Important notes

- **Stripe retries automatically.** Configure retry schedule in the Stripe Dashboard (Settings → Billing → Subscriptions and emails).
- **Notify the user early.** Most recoveries happen when the user updates their card.
- **Grace period.** Decide how long `past_due` users retain access — typically 3–7 days.

---

## 15. Billing Portal

**Use case:** A client wants to update their card, view invoices, or cancel — without SpotMe building that UI.

**Why Billing Portal:** Stripe-hosted, PCI compliant, handles payment method updates, invoice history, and cancellation.

### 15.1 Sequence

```
Client              API                  Stripe
  │                  │                     │
  │ POST /billing-portal                   │
  ├─────────────────►│                     │
  │                  │ ensure customer     │
  │                  │ create portal session
  │                  ├────────────────────►│
  │                  │◄────────────────────┤ { url }
  │◄─────────────────┤ { url }             │
  │                  │                     │
  │ open portal URL  │                     │
  ├───────────────────────────────────────►│
  │ user manages billing                   │
  │                  │◄────────────────────┤ webhooks
  │                  │                     │ (subscription.updated,
  │                  │                     │  payment_method.attached,
  │                  │                     │  invoice.*)
```

### 15.2 API handler

```ts
// POST /api/v1/billing-portal
export const createBillingPortal = async (req, res) => {
  const customer = await stripeService.ensureCustomer(req.user);

  const session = await stripe.billingPortal.sessions.create({
    customer: customer.id,
    return_url: `${env.CLIENT_URL}/dashboard/billing`,
  });

  res.json({ url: session.url });
};
```

### 15.3 State changes

| Object | Change |
|---|---|
| Whatever the user did in the portal | Mirrored via webhooks |

### 15.4 Events handled

- `customer.subscription.updated`
- `customer.subscription.deleted`
- `payment_method.attached`
- `payment_method.detached`
- `invoice.payment_succeeded`
- `invoice.payment_failed`

### 15.5 Important notes

- Configure the portal in the Stripe Dashboard (Settings → Billing → Customer portal) — enable cancellation, payment method updates, invoice history, and plan switching as desired.
- The portal is the **recommended** way to let users self-manage billing. Only build custom UI if you have a strong reason.

---

## 16. Webhook Handling Rules

### 16.1 Always verify the signature

```ts
const event = stripe.webhooks.constructEvent(
  req.body as Buffer,       // raw body, NOT parsed JSON
  req.headers['stripe-signature'] as string,
  env.STRIPE_WEBHOOK_SECRET
);
```

### 16.2 Always check idempotency

```ts
const existing = await WebhookEvent.findOne({ eventId: event.id });
if (existing) return res.json({ received: true, skipped: true });

// Use findOneAndUpdate with upsert for atomicity
await WebhookEvent.findOneAndUpdate(
  { eventId: event.id },
  { $setOnInsert: { eventId: event.id, type: event.type, processedAt: new Date() } },
  { upsert: true }
);
```

### 16.3 Always return 200 quickly

Process synchronously if fast. If a handler is slow (e.g., sending emails), enqueue and return `200` immediately. Stripe times out after 20 seconds.

### 16.4 Never trust the client's payment claim

Only webhooks confirm payment state. If a client says "I paid," your API should verify by retrieving the PaymentIntent from Stripe — but even then, the webhook is what mutates MongoDB.

### 16.5 Handle unknown events gracefully

Return `200` and log. Stripe adds new event types; your handler should not crash on unfamiliar ones.

### 16.6 Webhook event reference

| Event | Handler |
|---|---|
| `checkout.session.completed` | Fulfill package / activate subscription |
| `customer.subscription.created` | Insert local subscription |
| `customer.subscription.updated` | Sync status, plan, pause, cancel_at_period_end |
| `customer.subscription.deleted` | Mark canceled |
| `invoice.payment_succeeded` | Extend period, grant credits |
| `invoice.payment_failed` | Mark past_due, notify user |
| `payment_intent.succeeded` | Fulfill drop-in / tip / no-show |
| `payment_intent.payment_failed` | Mark failed, notify user |
| `payment_intent.requires_action` | Surface to client for authentication |
| `charge.refunded` | Revoke entitlements |
| `setup_intent.succeeded` | Save payment method |

---

## 17. Test Cards & Simulation

### 17.1 Common test cards

| Scenario | Card Number |
|---|---|
| Successful payment | `4242 4242 4242 4242` |
| Requires 3D Secure | `4000 0025 0000 3155` |
| Declined (generic) | `4000 0000 0000 9995` |
| Insufficient funds | `4000 0000 0000 9995` |
| Expired card | `4000 0000 0000 0069` |
| Processing error | `4000 0000 0000 0119` |
| 3DS required, then success | `4000 0000 0000 3220` |

Use any future expiry date, any 3-digit CVC, any postal code.

### 17.2 Stripe CLI

```bash
# Forward webhooks to local API
stripe listen --forward-to localhost:5000/api/v1/webhooks/stripe

# Trigger specific events
stripe trigger checkout.session.completed
stripe trigger customer.subscription.created
stripe trigger invoice.payment_failed
stripe trigger customer.subscription.deleted
stripe trigger payment_intent.succeeded
stripe trigger charge.refunded
stripe trigger setup_intent.succeeded
```

### 17.3 Test Clocks

Test Clocks simulate time passing — essential for testing renewals, trials, and credit expiry.

```bash
# Create a test clock
stripe test_helpers test_clocks create --frozen-time "2025-01-01T00:00:00Z"

# Create a customer attached to the clock
stripe customers create --test-clock clock_xxx --email test@example.com

# Advance time by 30 days
stripe test_helpers test_clocks advance clock_xxx --frozen-time "2025-01-31T00:00:00Z"
```

Use Test Clocks to verify:
- Monthly renewals and invoice generation
- Trial-to-paid conversion
- Credit expiry after 90 days
- Dunning after failed payments

### 17.4 3DS simulation

For PaymentSheet and Checkout, use `4000 0025 0000 3155` to force a 3DS challenge. The flow will present an authentication modal — you can complete or fail it.

For off-session charges, Stripe returns `authentication_required`. Your client must present the `client_secret` to complete authentication.

---

## 18. Common Pitfalls

| Pitfall | Why it hurts | Fix |
|---|---|---|
| Parsing webhook body with `express.json()` | Signature verification fails | Use `express.raw()` on the webhook route only |
| Trusting client "payment succeeded" | Client can lie | Only webhooks mutate payment state |
| Not idempotent webhook handlers | Duplicate credits, double charges | Store `event.id` in `webhookEvents` |
| Forgetting idempotency keys on POST | Duplicate PaymentIntents on retry | Pass `{ idempotencyKey }` to every create |
| Shipping `sk_test_` in a mobile app | Secret key leaked | Only `pk_` on clients; `sk_` on server |
| Hardcoding prices | Can't change without deploy | Use Stripe Price IDs stored in MongoDB |
| Using `off_session: true` without SetupIntent | Charge fails with SCA | Save card via SetupIntent first |
| Ignoring `payment_intent.requires_action` | User can't complete payment | Surface `client_secret` to client |
| Leaving manual captures hanging | Authorization expires after 7 days | Cancel or capture before expiry |
| Not handling `customer.subscription.deleted` | Users retain access after cancel | Revoke entitlement on deletion |
| No rate limiting on `/checkout/*` | Abuse, card testing | `express-rate-limit` per user/IP |
| Logging full request bodies on payment routes | PII/card data leaked | Redact sensitive fields |
| No test for webhook retries | Bugs only surface in production | Use `stripe trigger` and simulate retries |

---

## Appendix A — Idempotency Key Reference

| Flow | Key format |
|---|---|
| Subscription checkout | `sub-checkout-{userId}-{planId}` |
| Package checkout | `pkg-checkout-{userId}-{packageId}` |
| Drop-in PaymentIntent | `drop-in-{userId}-{sessionId}` |
| No-show fee | `no-show-{bookingId}` |
| Deposit | `deposit-{bookingId}` |
| Deposit capture | `deposit-capture-{bookingId}` |
| Pause | `pause-{subscriptionId}-{resumeAt}` |
| Plan change | `change-plan-{subscriptionId}-{newPlanId}` |
| Refund | `refund-{paymentIntentId}-{amountCents|full}` |

---

## Appendix B — Entitlement Rules

| Entitlement | Source | Check |
|---|---|---|
| Access to subscriber content | Active subscription | `subscription.status === 'active'` |
| Book a session with credit | Credits > 0 | `credit.remaining > 0 && credit.expiresAt > now` |
| Book a drop-in | Payment | `booking.status === 'paid'` |
| Pause membership | Active subscription | `subscription.status === 'active'` |
| Off-session charge | Saved payment method | `user.defaultPaymentMethodId != null` |

---

<p align="center">
  <em>Every payment is a promise. Stripe is how SpotMe keeps it.</em>
</p>
