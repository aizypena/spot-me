# SpotMe — Design System

> A single source of truth for SpotMe's visual language. The same tokens, components, and patterns are used across **React (web)**, **Android (Java)**, and **iOS (Swift)** so the product feels identical everywhere.

This document is the contract. If a value isn't here, it doesn't ship.

---

## Table of Contents

- [1. Philosophy](#1-philosophy)
- [2. Design Tokens Overview](#2-design-tokens-overview)
- [3. Color](#3-color)
- [4. Typography](#4-typography)
- [5. Spacing](#5-spacing)
- [6. Radius](#6-radius)
- [7. Elevation & Shadows](#7-elevation--shadows)
- [8. Iconography](#8-iconography)
- [9. Motion](#9-motion)
- [10. Layout & Grid](#10-layout--grid)
- [11. Components](#11-components)
- [12. Patterns](#12-patterns)
- [13. Accessibility](#13-accessibility)
- [14. Platform Implementation](#14-platform-implementation)
- [15. Contribution Rules](#15-contribution-rules)

---

## 1. Philosophy

SpotMe's design language is built on four ideas:

1. **Energy with trust.** Fitness is motivating; payments are serious. The palette and typography balance warmth with reliability.
2. **One token, three platforms.** Every value — color, size, radius, shadow — has the same name in CSS, Android, and iOS. No drift.
3. **Semantic over literal.** Tokens are named by *role* (`text-secondary`, `radius-card`), not by appearance (`gray-500`, `radius-8`).
4. **Accessible by default.** Every text-on-background combination meets WCAG AA. Every interactive element has a visible focus state. Every state is conveyed by more than color alone.

The result is a system that feels native on each platform while remaining unmistakably SpotMe.

---

## 2. Design Tokens Overview

Tokens are grouped into five families:

| Family | Purpose | Example |
|---|---|---|
| **Color** | Brand, semantic, surface, text, border | `color-primary-500` |
| **Typography** | Font, size, weight, line height | `text-heading-lg` |
| **Spacing** | Padding, margin, gap | `space-4` |
| **Radius** | Corner rounding | `radius-lg` |
| **Elevation** | Shadow depth | `elevation-2` |

Every token has a **platform-specific name** but a **single conceptual identity**:

| Concept | Web (CSS) | Web (Tailwind) | Android | iOS |
|---|---|---|---|---|
| Primary 500 | `--color-primary-500` | `bg-primary-500` | `R.color.primary_500` | `Color.primary500` |
| Heading LG | `--text-heading-lg` | `text-heading-lg` | `R.dimen.text_heading_lg` | `.headingLG` |
| Space 4 | `--space-4` | `p-4` | `R.dimen.space_4` | `.space4` |

---

## 3. Color

Full color specification lives in the color palette document. This section is a summary for quick reference.

### 3.1 Brand

| Token | Hex | Role |
|---|---|---|
| `primary-500` | `#FF5A1F` | Primary action, brand mark |
| `secondary-900` | `#0F172A` | Trust, headers, dark surfaces |
| `accent-500` | `#14B8A6` | Health, progress, success accents |

### 3.2 Semantic

| Token | Light | Dark | Role |
|---|---|---|---|
| `success` | `#10B981` | `#34D399` | Payment succeeded |
| `warning` | `#F59E0B` | `#FBBF24` | Past due, attention |
| `error` | `#EF4444` | `#F87171` | Payment failed |
| `info` | `#3B82F6` | `#60A5FA` | Informational |

### 3.3 Surfaces

| Token | Light | Dark |
|---|---|---|
| `bg-base` | `#FFFFFF` | `#0F172A` |
| `bg-surface` | `#F8FAFC` | `#1E293B` |
| `bg-elevated` | `#FFFFFF` | `#334155` |

### 3.4 Text

| Token | Light | Dark |
|---|---|---|
| `text-primary` | `#0F172A` | `#F8FAFC` |
| `text-secondary` | `#475569` | `#CBD5E1` |
| `text-muted` | `#94A3B8` | `#94A3B8` |
| `text-inverse` | `#FFFFFF` | `#0F172A` |

### 3.5 Rules

- Never hardcode a hex value in a component. Always use a token.
- Never rely on color alone to convey state — pair with an icon or label.
- Use `bg-surface` for cards, `bg-elevated` for modals. Do not skip levels.

---

## 4. Typography

### 4.1 Typeface

| Platform | Font |
|---|---|
| Web | **Inter** (Google Fonts) with `system-ui` fallback |
| Android | **Inter** (bundled in `res/font/`) with `sans-serif` fallback |
| iOS | **Inter** (bundled) with `SF Pro` fallback |

Inter is chosen for its excellent legibility at small sizes, wide language coverage, and neutral tone.

### 4.2 Type scale

| Token | Size | Line height | Weight | Use |
|---|---|---|---|---|
| `text-display` | 48px | 56px | 700 | Marketing hero |
| `text-heading-xl` | 32px | 40px | 700 | Page titles |
| `text-heading-lg` | 24px | 32px | 600 | Section headers |
| `text-heading-md` | 20px | 28px | 600 | Card titles |
| `text-heading-sm` | 18px | 24px | 600 | Subsection headers |
| `text-body-lg` | 16px | 24px | 400 | Primary body |
| `text-body-md` | 14px | 20px | 400 | Default body |
| `text-body-sm` | 13px | 18px | 400 | Secondary body |
| `text-caption` | 12px | 16px | 400 | Captions, hints |
| `text-overline` | 11px | 16px | 600 | Labels, tags (uppercase) |
| `text-mono` | 14px | 20px | 400 | Code, IDs |

### 4.3 Rules

- Body text is `text-body-md` (14px) on mobile, `text-body-lg` (16px) on desktop.
- Never use more than **three weights** on a single screen.
- Never use `text-caption` for body copy — captions are for hints only.
- Line length: 45–75 characters for body. Never exceed 80.

### 4.4 Platform tokens

| Concept | Web | Android | iOS |
|---|---|---|---|
| Heading LG | `.text-heading-lg` | `@style/Text.Heading.LG` | `.headingLG` |
| Body MD | `.text-body-md` | `@style/Text.Body.MD` | `.bodyMD` |

---

## 5. Spacing

### 5.1 Scale

SpotMe uses a **4px base unit**. All spacing values are multiples of 4.

| Token | Value | Use |
|---|---|---|
| `space-0` | 0px | Reset |
| `space-1` | 4px | Tight gaps |
| `space-2` | 8px | Icon-to-label |
| `space-3` | 12px | Compact padding |
| `space-4` | 16px | Default padding |
| `space-5` | 20px | Card padding |
| `space-6` | 24px | Section padding |
| `space-8` | 32px | Large gaps |
| `space-10` | 40px | Section separation |
| `space-12` | 48px | Page sections |
| `space-16` | 64px | Hero padding |
| `space-20` | 80px | Landing sections |

### 5.2 Rules

- Never use arbitrary values. If you need `17px`, you need `space-4` (16px) or `space-5` (20px).
- Padding inside a component uses `space-4` by default; dense components use `space-3`.
- Vertical rhythm between sections uses `space-10` (mobile) or `space-16` (desktop).
- Gutters between grid columns use `space-4` (mobile) or `space-6` (desktop).

---

## 6. Radius

| Token | Value | Use |
|---|---|---|
| `radius-none` | 0px | Sharp corners |
| `radius-sm` | 4px | Tags, badges |
| `radius-md` | 6px | Inputs, small buttons |
| `radius-lg` | 8px | Buttons, cards |
| `radius-xl` | 12px | Large cards, modals |
| `radius-2xl` | 16px | Hero cards |
| `radius-full` | 9999px | Pills, avatars |

### Rules

- Buttons and inputs: `radius-lg` (8px) on web, `radius-md` (6px) on mobile — mobile uses tighter corners.
- Cards: `radius-xl` (12px).
- Modals: `radius-xl` (12px) on mobile, `radius-2xl` (16px) on desktop.
- Avatars and pills: `radius-full`.

---

## 7. Elevation & Shadows

Shadows convey depth. SpotMe uses **six elevation levels**.

| Token | Shadow | Use |
|---|---|---|
| `elevation-0` | none | Flat surfaces |
| `elevation-1` | `0 1px 2px rgba(15,23,42,0.06)` | Cards at rest |
| `elevation-2` | `0 2px 4px rgba(15,23,42,0.08)` | Cards on hover, dropdowns |
| `elevation-3` | `0 4px 12px rgba(15,23,42,0.10)` | Popovers, tooltips |
| `elevation-4` | `0 8px 24px rgba(15,23,42,0.12)` | Modals |
| `elevation-5` | `0 16px 48px rgba(15,23,42,0.16)` | Fullscreen overlays |

### Rules

- Prefer **one** elevation per screen region. Nested shadows look muddy.
- On dark backgrounds, shadows are less effective — use `border-default` (1px) instead.
- Never combine elevation with a heavy border. Pick one.

---

## 8. Iconography

### 8.1 Icon set

SpotMe uses **Lucide** icons across all platforms:

- **Web:** `lucide-react`
- **Android:** vector drawables exported from Lucide SVG
- **iOS:** SF Symbols where possible, Lucide SVG fallback

### 8.2 Sizes

| Token | Value | Use |
|---|---|---|
| `icon-xs` | 12px | Inline with caption |
| `icon-sm` | 16px | Inline with body |
| `icon-md` | 20px | Buttons, inputs |
| `icon-lg` | 24px | Nav, headers |
| `icon-xl` | 32px | Feature highlights |
| `icon-2xl` | 48px | Empty states |

### 8.3 Rules

- Icons inherit `currentColor`. Never hardcode an icon color.
- Stroke width: **1.5px** for icons ≤ 20px, **2px** for larger.
- Interactive icons must have a minimum tap target of **44×44px** (mobile).
- Never use an icon without an accessible label (`aria-label` on web, `contentDescription` on Android).

---

## 9. Motion

Motion reinforces hierarchy. SpotMe uses motion to **confirm**, **orient**, and **guide** — never to decorate.

### 9.1 Durations

| Token | Value | Use |
|---|---|---|
| `duration-instant` | 0ms | State that shouldn't animate |
| `duration-fast` | 150ms | Hover, focus, small toggles |
| `duration-normal` | 250ms | Modal, dropdown, most transitions |
| `duration-slow` | 400ms | Page transitions, large reveals |
| `duration-slower` | 600ms | Hero animations |

### 9.2 Easings

| Token | Curve | Use |
|---|---|---|
| `ease-out` | `cubic-bezier(0.0, 0.0, 0.2, 1)` | Enter animations |
| `ease-in` | `cubic-bezier(0.4, 0.0, 1, 1)` | Exit animations |
| `ease-in-out` | `cubic-bezier(0.4, 0.0, 0.2, 1)` | On-screen transitions |
| `ease-spring` | `cubic-bezier(0.34, 1.56, 0.64, 1)` | Playful confirms |

### 9.3 Rules

- Respect **reduce motion** — check `prefers-reduced-motion` on web, `Settings.Global.ANIMATOR_DURATION_SCALE` on Android, `UIAccessibility.isReduceMotionEnabled` on iOS.
- Never animate **layout-affecting** properties (width, height, top, left) on the web — use transforms.
- Payment confirmations may use `ease-spring` for a satisfying success cue.
- Loading skeletons use `duration-slower` with a subtle pulse.

---

## 10. Layout & Grid

### 10.1 Breakpoints (web)

| Token | Value | Use |
|---|---|---|
| `bp-sm` | 640px | Large phones |
| `bp-md` | 768px | Tablets |
| `bp-lg` | 1024px | Small laptops |
| `bp-xl` | 1280px | Desktops |
| `bp-2xl` | 1536px | Large displays |

### 10.2 Grid

| Context | Columns | Gutter | Margin |
|---|---|---|---|
| Mobile | 4 | 16px | 16px |
| Tablet | 8 | 24px | 24px |
| Desktop | 12 | 24px | 32px |
| Max width | — | — | 1280px |

### 10.3 Rules

- Content max-width: **720px** for prose, **1280px** for dashboards.
- Never let text lines exceed 80 characters.
- Tap targets: minimum **44×44px** (iOS) / **48×48dp** (Android).

---

## 11. Components

Each component has a defined set of states, sizes, and tokens. Full specs live in `/docs/components/` (linked below).

### 11.1 Core components

| Component | Description | Spec |
|---|---|---|
| Button | Primary, secondary, ghost, danger | [button.md](./components/button.md) |
| Input | Text, email, password, number | [input.md](./components/input.md) |
| Select | Single-select dropdown | [select.md](./components/select.md) |
| Checkbox / Radio | Boolean and single-select | [checkbox.md](./components/checkbox.md) |
| Card | Content container | [card.md](./components/card.md) |
| Modal | Focused overlay | [modal.md](./components/modal.md) |
| Toast | Non-blocking notification | [toast.md](./components/toast.md) |
| Avatar | User identity | [avatar.md](./components/avatar.md) |
| Badge | Status indicator | [badge.md](./components/badge.md) |
| Tag | Filter/label chip | [tag.md](./components/tag.md) |
| Tabs | Sectioned content | [tabs.md](./components/tabs.md) |
| Tooltip | Contextual hint | [tooltip.md](./components/tooltip.md) |

### 11.2 Domain components

| Component | Description |
|---|---|
| TrainerCard | Trainer summary with avatar, rate, subscribe button |
| PlanCard | Membership plan with price, perks, CTA |
| SessionSlot | Bookable time slot with availability state |
| BookingRow | Single booking with status badge |
| CreditMeter | Visual credit balance |
| SubscriptionStatus | Active / paused / past_due chip |
| PaymentMethodRow | Saved card with brand icon and last4 |
| ProgressChart | Weight/measurement trend line |

### 11.3 Button spec (reference)

**Sizes:**

| Size | Height | Padding | Text |
|---|---|---|---|
| `sm` | 32px | `space-3` | `text-body-sm` |
| `md` | 40px | `space-4` | `text-body-md` |
| `lg` | 48px | `space-5` | `text-body-lg` |

**Variants:**

| Variant | Background | Text | Border |
|---|---|---|---|
| Primary | `primary-500` | `text-inverse` | none |
| Secondary | `secondary-900` | `text-inverse` | none |
| Ghost | transparent | `primary-500` | none |
| Outline | transparent | `text-primary` | `border-default` |
| Danger | `error` | `text-inverse` | none |

**States:**

| State | Effect |
|---|---|
| Default | Base styles |
| Hover | `primary-400` (primary), opacity 0.9 (others) |
| Focus | 2px ring in `border-focus`, offset 2px |
| Pressed | `primary-600` (primary), scale 0.98 |
| Disabled | opacity 0.5, cursor not-allowed |
| Loading | Spinner replaces text, width locked |

### 11.4 Input spec (reference)

| Property | Value |
|---|---|
| Height | 40px (`md`), 48px (`lg`) |
| Padding | `space-4` horizontal, `space-3` vertical |
| Border | 1px `border-default` |
| Radius | `radius-md` |
| Focus | Border `border-focus`, 2px ring `border-focus/20` |
| Error | Border `border-error`, helper text `error` |
| Disabled | `bg-muted`, `text-disabled` |

---

## 12. Patterns

Patterns are reusable solutions to recurring UI problems.

### 12.1 Empty states

Every list has an empty state with:
- An icon (`icon-2xl`, `text-muted`)
- A short heading (`text-heading-md`)
- One sentence of guidance (`text-body-md`, `text-secondary`)
- A primary action button

### 12.2 Loading states

| Duration | Pattern |
|---|---|
| < 300ms | No indicator (feels instant) |
| 300ms – 1s | Inline spinner |
| > 1s | Skeleton matching content shape |
| > 5s | Skeleton + progress message |

### 12.3 Error states

- **Inline** (form fields): red border, red helper text, icon.
- **Section** (card-level): red banner at top of section, retry action.
- **Page** (unrecoverable): full-page illustration, explanation, retry / go back.

### 12.4 Payment flows

- Always show a **summary** of what the user is paying for before confirmation.
- Show **Stripe Elements / PaymentSheet** as the sole card-entry surface. Never build custom card inputs.
- After success, show a **confirmation screen** with:
  - Green checkmark (`success`)
  - What was purchased
  - What happens next
  - A single primary action

### 12.5 Destructive actions

- Require confirmation via a modal.
- Modal must state the consequence in plain language.
- Danger button is the primary action; cancel is secondary.
- For irreversible actions, require typing the resource name (e.g., "cancel subscription").

---

## 13. Accessibility

### 13.1 Standards

SpotMe targets **WCAG 2.1 AA**.

| Requirement | Target |
|---|---|
| Body text contrast | ≥ 4.5:1 |
| Large text (≥ 18px bold / 24px) contrast | ≥ 3:1 |
| UI component contrast | ≥ 3:1 |
| Focus indicator | Visible, ≥ 2px |
| Tap target | ≥ 44×44px (iOS), ≥ 48×48dp (Android) |

### 13.2 Rules

- Every interactive element is keyboard reachable (web).
- Every image has alt text; decorative images use `alt=""`.
- Every icon-only button has `aria-label` (web) or `contentDescription` (Android).
- Every form field has an associated `<label>`.
- Errors are announced to screen readers (`aria-live="polite"`).
- Never disable zoom on web (`user-scalable=no` is forbidden).
- Respect `prefers-reduced-motion`, `prefers-color-scheme`, and `prefers-contrast`.

### 13.3 Testing

- Automated: `axe-core` in CI for web, `Accessibility Scanner` for Android, `Accessibility Inspector` for iOS.
- Manual: keyboard-only pass, screen reader pass, 200% zoom pass.

---

## 14. Platform Implementation

### 14.1 Web

- Tokens live in `apps/web/src/styles/tokens.css`.
- Tailwind config maps tokens to utility classes.
- Components in `apps/web/src/components/ui/`.
- Dark mode: `[data-theme="dark"]` on `<html>`.

### 14.2 Android

- Tokens in `apps/android/app/src/main/res/values/`.
- Dark mode: `values-night/` directory.
- Theme in `themes.xml` with `Theme.Material3.DayNight`.
- Components in `ui/common/`.

### 14.3 iOS

- Colors in `Assets.xcassets` as Color Sets (light + dark).
- Typography in `Shared/Theme/Typography.swift`.
- Components in `Shared/Components/`.
- Dark mode: handled by system appearance.

### 14.4 Cross-platform parity

| Concern | How parity is enforced |
|---|---|
| Colors | Same hex in all three token files |
| Typography | Same size/weight/line-height values |
| Spacing | Same 4px scale |
| Radius | Same values (mobile may use one step smaller) |
| Motion | Same durations and easings |
| Components | Same states, same labels, same order |

**Rule:** if a token changes, it changes in all three platforms in the same PR.

---

## 15. Contribution Rules

1. **Add tokens, don't hardcode.** Every new color, size, or radius must become a token first.
2. **Update all three platforms.** A token that exists only on web is a bug.
3. **Document the use case.** Every token in this file has a "use" column for a reason.
4. **Test in dark mode.** Every component must be verified in both themes.
5. **Test with keyboard.** Every interactive component must be reachable and usable without a mouse.
6. **Test with a screen reader.** Every component must announce its purpose and state.
7. **Never break the contract.** Removing or renaming a token is a breaking change and requires a migration plan.

---

## Appendix A — Token Reference (Quick Lookup)

```
COLOR
  primary-500       #FF5A1F
  secondary-900     #0F172A
  accent-500        #14B8A6
  success           #10B981
  warning           #F59E0B
  error             #EF4444
  info              #3B82F6
  bg-base           #FFFFFF
  bg-surface        #F8FAFC
  bg-elevated       #FFFFFF
  text-primary      #0F172A
  text-secondary    #475569
  text-muted        #94A3B8
  border-default    #E2E8F0
  border-focus      #FF5A1F

TYPOGRAPHY
  display           48/56  700
  heading-xl        32/40  700
  heading-lg        24/32  600
  heading-md        20/28  600
  heading-sm        18/24  600
  body-lg           16/24  400
  body-md           14/20  400
  body-sm           13/18  400
  caption           12/16  400
  overline          11/16  600

SPACING       4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80
RADIUS        0, 4, 6, 8, 12, 16, 9999
ELEVATION     0, 1, 2, 3, 4, 5
MOTION        150ms, 250ms, 400ms, 600ms
```

---

<p align="center">
  <em>Consistency is a feature. This document is how we ship it.</em>
</p>
