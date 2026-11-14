---
version: "1.0"
name: Munchkin-Dungeon-Edition
description: >-
  Cross-platform design system for Munchkin Mesa Tracker (Android Jetpack Compose + Web PWA).
  Warm dark obsidian-stone surfaces paired with Crimson Torch (#CC2020), Dungeon Gold (#D4960A),
  Ember Orange (#E85D00), and high-contrast warm parchment typography. Zero generic purple/violet
  AI-slop, zero nested rounded cards-inside-cards.
---

# Munchkin Mesa Tracker — Design System (`DESIGN.md`)

## 1. Visual Theme & Atmosphere ("Dungeon Edition")
- **Aesthetic Direction:** Tabletop dungeon torchlight over dark stone & aged parchment — tactile, warm, high-contrast, legible across dimly lit game tables and bright handheld screens.
- **Strict Anti-Slop Rules:**
  - **NO Purple / Violet anywhere** (prohibited on backgrounds, gradients, buttons, borders, glow rings, manifests, or SVG strokes across both Android and Web PWA).
  - **NO `cards-inside-cards`:** Panels and cards may contain flat inset rows, `1px` hairline dividers, or compact chips — never rounded bordered cards nested inside rounded bordered cards.
  - **Realistic Tabletop Data:** All dice, combat totals, bad stuff text, and player standings use `tabular-nums` / monospace numeric alignment.

---

## 2. Color Tokens & WCAG AA Contrast Rules

| Semantic Role | Token | Hex Value | Usage | Contrast Pairing |
|---|---|---|---|---|
| **Base Canvas** | `bg` / `NeonBackground` | `#0F0A08` | Root app background | Primary ink `#FAF7F0` (18.4:1) |
| **Elevated Surface** | `surface-1` / `NeonSurface` | `#1A110D` | Cards, panels, modals, sheets | Primary ink `#FAF7F0` (16.9:1) |
| **Surface Variant** | `surface-2` / `NeonSurfaceVariant` | `#271A14` | Table rows, input fills, inset blocks | Secondary ink `#D8CFC0` (10.7:1) |
| **Hairline Border** | `border` | `#3E2A20` | `1px` panel/row dividers & borders | Subtle structural separation |
| **Primary Accent** | `primary` / `NeonPrimary` | `#CC2020` | Primary CTA, monster menace, level badge | `#FAF7F0` text (4.9:1 AA pass) |
| **Primary Hover/Light** | `primary-light` | `#E05050` | Highlight accents / non-text strokes | On `#0F0A08` (5.0:1) |
| **Secondary Accent** | `secondary` / `NeonSecondary` | `#D4960A` | Treasure gold, active turn indicator, host crown | **Dark ink `#1A110D`** (8.6:1 AA pass; NEVER white text on `#D4960A`) |
| **Tertiary Accent** | `tertiary` / `NeonCyan` | `#E85D00` | Torch ember, End Turn / Dice highlights | **Dark ink `#0F0A08`** on fills |
| **Success** | `success` | `#2EA043` (Web fill) / `#00E676` (Dark-surface ink) | Combat winning edge, connected pill | `#FAF7F0` on `#1E7E34` or dark ink `#0F0A08` on `#00E676` |
| **Danger** | `danger` | `#B91C1C` (filled btn) / `#E5534B` (text on dark) | Destructive actions, Combat losing state | `#FAF7F0` on `#B91C1C` (5.9:1) |
| **Warning** | `warning` | `#FFD600` | Turn countdown low time (`<= 10s`) | Dark ink `#0F0A08` |
| **Primary Text** | `text` / `NeonWhite` | `#FAF7F0` | Headings, active labels, numbers | `>= 15:1` on dark surfaces |
| **Secondary Text** | `text-secondary` / `NeonGray200` | `#D8CFC0` | Body copy, subtitles, descriptions | `>= 10:1` on `#1A110D` |
| **Muted Text** | `text-muted` / `NeonGray300` | `#B8A898` | Helper copy, timestamps, labels | `>= 7.5:1` on `#1A110D` |
| **Hint / Disabled Text** | `text-hint` / `NeonGray400` | `#8A7A6A` | Placeholders & inactive icons (minimum legible gray on dark surfaces) | `>= 4.6:1` on `#0F0A08`/`#1A110D` |

---

## 3. Canonical 8-Avatar Mapping (`0..7`)
Shared identically across Android (`AvatarResources.kt`) and Web PWA (`server/public/js/i18n.mjs`):

| ID | Key | ES | EN | FR | Theme Accent |
|---|---|---|---|---|---|
| `0` | `WARRIOR` | Guerrero | Warrior | Guerrier | `#CC2020` |
| `1` | `WIZARD` | Mago | Wizard | Magicien | `#D4960A` |
| `2` | `THIEF` | Ladrón | Thief | Voleur | `#E85D00` |
| `3` | `CLERIC` | Clérigo | Cleric | Clerc | `#00E676` |
| `4` | `HUMAN` | Humano | Human | Humain | `#8B6914` |
| `5` | `ELF` | Elfo | Elf | Elfe | `#FFD600` |
| `6` | `DWARF` | Enano | Dwarf | Nain | `#E5534B` |
| `7` | `HALFLING` | Mediano | Halfling | Halfelin | `#8D4025` |

---

## 4. Typography, Spacing & Touch Targets
- **Font Hierarchy:**
  - Android: Display (`24–32sp` Bold), Title (`18–20sp` SemiBold), Body (`14–16sp`), Label (`11–13sp` Medium), with optional `largeNumbersMode` (`1.3x` scale on numeric gameplay stats).
  - Web PWA: `'Plus Jakarta Sans', system-ui, sans-serif` + `'JetBrains Mono', ui-monospace, monospace` for join codes, timer, combat totals, and `tabular-nums`.
- **Spacing Scale:** Strict `4px` / `4dp` grid (`4, 8, 12, 16, 20, 24, 32`).
- **Minimum Touch Targets:**
  - Android Compose: `>= 48.dp` width & height on all interactive buttons, icon buttons, steppers, and avatar tiles.
  - Web PWA: `>= 44px` width & height on `.btn`, `.icon-btn`, `.stepper button`, and bottom-nav tabs.
- **Accessibility & Focus:**
  - Android: `contentDescription` localized across `es`/`en`/`fr` on all meaningful icons and avatars; root `SnackbarHost`; `imePadding()` + `navigationBarsPadding()` + `verticalScroll` on forms.
  - Web: Visible `:focus-visible { outline: 2px solid #D4960A; outline-offset: 2px; }`, accessible modals (`role="dialog"`, `aria-modal="true"`, `aria-labelledby`, focus trap, `Escape` key), `aria-live="polite"` toast container, zero inline `style="..."` attributes (strict `style-src 'self'` CSP).

---

## 5. QR & Deep-Link Contract
- QR codes encode `https://munchking-sirpepo.duckdns.org/#/join/<CODE>` (or configured `<ServerConfig.HOST>`).
- QR payloads NEVER embed custom `wsUrl` hosts. Android & Web extract strictly the 6–8 char alphanumeric room code and connect only to the configured authoritative server.
