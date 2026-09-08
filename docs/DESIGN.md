---
name: CopilotGo native design system
description: A restrained conversation canvas for reading, composing, and inspecting real tool activity.
colors:
  light-primary: "#202123"
  light-on-primary: "#FFFFFF"
  light-canvas: "#FFFFFF"
  light-on-canvas: "#202123"
  light-muted: "#5C5D62"
  light-user-bubble: "#F0F0F1"
  light-composer: "#F1F1F2"
  light-raised: "#E8E8EA"
  light-disabled: "#E1E1E3"
  light-divider: "#DCDCDD"
  light-error: "#B3261E"
  light-error-surface: "#FCE8E6"
  light-on-error-surface: "#6D1310"
  dark-primary: "#ECECEC"
  dark-on-primary: "#202020"
  dark-canvas: "#171717"
  dark-on-canvas: "#ECECEC"
  dark-muted: "#B4B4B8"
  dark-user-bubble: "#303030"
  dark-composer: "#262626"
  dark-raised: "#303030"
  dark-disabled: "#3A3A3A"
  dark-divider: "#424244"
  dark-error: "#FFB4AB"
  dark-error-surface: "#4A1D1B"
  dark-on-error-surface: "#FFDAD6"
typography:
  headline-medium:
    fontFamily: "sans-serif"
    fontSize: "28sp"
    fontWeight: 500
    lineHeight: "36sp"
  headline-small:
    fontFamily: "sans-serif"
    fontSize: "24sp"
    fontWeight: 500
    lineHeight: "32sp"
  title-large:
    fontFamily: "sans-serif"
    fontSize: "20sp"
    fontWeight: 500
    lineHeight: "28sp"
  title-medium:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "24sp"
  body-large:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "26sp"
  body-medium:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "22sp"
  label-medium:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
rounded:
  extra-small: "8dp"
  small: "12dp"
  medium: "16dp"
  large: "20dp"
  extra-large: "28dp"
spacing:
  compact-padding: "4dp"
  control-gap: "8dp"
  composer-gutter: "12dp"
  content-padding: "16dp"
  page-gutter: "20dp"
  state-padding: "24dp"
components:
  send-light:
    backgroundColor: "{colors.light-primary}"
    textColor: "{colors.light-on-primary}"
    size: "48dp"
  send-dark:
    backgroundColor: "{colors.dark-primary}"
    textColor: "{colors.dark-on-primary}"
    size: "48dp"
  user-bubble:
    rounded: "{rounded.large}"
    padding: "16dp"
    width: "600dp maximum"
  composer:
    rounded: "{rounded.extra-large}"
    height: "72dp nominal compact layout; expands with content"
    width: "760dp maximum"
---

# CopilotGo native design system

## Overview

The conversation is a reading and composing surface, not a stack of labeled
diagnostic cards. A neutral canvas, user-only bubbles, a concise title/model
header, and one rounded composer make the content the primary hierarchy.
Tool provenance remains truthful and available without competing with every
paragraph.

This is the built Android system, recorded after the two reference-led native
review batches and their functional corrections. It is not a pre-build mood
board or a claim of affiliation with another product. Product capabilities and
limits remain in [PRODUCT.md](../PRODUCT.md).

The authoritative implementation is `ui/theme/Color.kt`, `Theme.kt`, `Type.kt`,
and `Layout.kt` under `app/src/main/java/com/tongxie/copilotgo/`. Values here use
native `dp` and `sp`, not browser pixels. Unlisted Material roles retain their
actual values in `Theme.kt`; do not invent a second palette in a screen.

## Colors

`CopilotGoTheme` follows system light/dark mode and defaults to the static neutral
schemes. The existing `dynamicColor` parameter is an explicit opt-in; wallpaper
colors must not silently reintroduce a blue or green application identity.

Use `MaterialTheme.colorScheme`, not raw colors inside components. Assistant
content uses `onSurface` directly on the canvas. User bubbles pair
`secondaryContainer` with `onSecondaryContainer`; composer and code surfaces
use the tonal container roles. Muted text uses `onSurfaceVariant`, not arbitrary
opacity that weakens readability. Error surfaces retain their semantic red
pairings.

Filled Send and Stop controls pair `primary` with `onPrimary`. When customizing
Material 3 `filledIconButtonColors`, explicitly supply both enabled colors as
well as any disabled overrides: a partial override can inherit the composer's
foreground and render a black glyph on a black button. This is covered by an
actual native pixel regression, not only a token contrast calculation.

Body and placeholder contrast must remain at least 4.5:1. The native glyph
regression also requires at least 4.5:1 for enabled Send/Stop in both themes.
Do not infer a visible control from its semantic node alone.

## Typography

Text uses the Android system sans-serif family and its CJK/emoji fallbacks.
There is no bundled display face. Code retains the existing monospace renderer;
monospace is not a decorative style for navigation or settings.

Reading text is `bodyLarge` (16sp/26sp), secondary descriptions `bodyMedium`
(14sp/22sp), and compact metadata the label/body-small roles. Screen titles use
`titleLarge`; the conversation's title/model entry uses the smaller hierarchy
instead of a large headline plus a second model-selection band. Long titles
may ellipsize in chrome; full model information remains in the real selector.

All text continues to scale with the system font setting. Layout adapts to
large fonts; it does not clamp font scale to preserve a screenshot.

## Layout

`AppLayout` supplies a 760dp maximum conversation measure, 840dp page width,
600dp user-bubble maximum, 20dp reading gutters, 12dp composer gutters, and
48dp control size. The user bubble adds a 24dp leading inset before its width
limit. Content can occupy less than the maximum when its intrinsic width is
smaller.

The conversation header is one 64dp control hierarchy. Model and mode retain
separate named actions without restoring competing full-width bands. History
places search and new conversation in the app bar, with secondary destinations
in the compact menu; expanded history uses the existing native navigation rail
at the 840dp breakpoint.

The ordinary compact composer is nominally 72dp including its vertical outer
spacing (72.38dp in the 420dpi acceptance capture because of pixel rounding).
Its measured inner width must be at least 320dp, font scale at most 1.2, and
the bounded short draft must actually fit one line. Attachments, import state,
recovery notices, supporting text, disabled editing, line breaks, and longer
text use the expanded layout. This is a layout choice, not a text limit.

The separate short-wide path activates for at least 560dp internal width and
less than 200dp available height. Both arrangements place the same
`BasicTextField` and measured layout children; they do not recreate an editor
when the keyboard or draft geometry changes.

System bars, cutouts, and the real IME are handled by the production scaffolds.
Do not consume the same inset twice or measure only the Send button while
allowing the editor to overflow its viewport. Manual reading relinquishes
follow-tail; jump-to-latest remains a deliberate, visible action.

## Elevation & Depth

The interface is flat by default. Tonal surface differences distinguish user
bubbles, the composer, code, and semantic feedback. Native menus, dialogs, and
other Material components retain their platform elevation behavior; no custom
decorative shadow, blur, gradient, or glass layer is introduced.

Assistant paragraphs have no enclosing tinted card. Separators are reserved
for useful grouping, not repeated around every history row or tool label.

## Shapes

`AppShapes` uses 8/12/16/20/28dp corner radii. The user bubble uses 20dp and the
composer 28dp; the assistant reading surface is rectangular and transparent.
Code header and body form one coherent panel rather than differently sized
nested rounded surfaces.

## Components

**Messages.** User messages are right-aligned, neutral bubbles. Assistant
Markdown is borderless. Copy and overflow are quiet post-message controls for
ordinary messages and Agent replies alike, including replies with no sources.
Their availability must not depend on whether an Agent source list exists.
Edit/regenerate/delete preserve the existing busy and uncertain-replay guards;
export and copy remain available where their existing contracts permit.

**Composer.** Add, real system speech-to-text, and Send/Stop belong to one soft
surface. Each icon has a named 48dp target. Add still opens the actual
image/text-file choices. Multiline content, attachments, asynchronous admission,
and draft recovery retain their original lifecycle and accepted-send behavior.

**Code and reading.** Code has a quiet language/action header with 48dp copy and
wrap controls. Wrapping, horizontal reading, selection, tables, math, bounded
parsing, safe links, and final streaming updates retain the existing renderer.
Unknown citation markers and markers inside code are not made clickable.

**Agent and sources.** Ordinary completed/running activity uses a concise
disclosure with real step counts. Pending approval, errors, and uncertain
outcomes remain visible and actionable. Answer source rows show title and
ID/domain; the detail surface keeps the full URL, source kind, excerpt,
destination, arguments, and actual result. Source rows never overlap the
message body or retarget a citation to another run.

**History and settings.** History prioritizes the title, preview, and date
instead of repeating message/model metadata and heavy dividers. Login,
account, files, proxy, tool settings, storage, About, and update dialogs share
the same neutral typography and grouping. Persistent storage recovery keeps
its retry action visible; do not add a duplicate Snackbar over that surface.

**Remote.** App-owned chrome may use the shared theme. The remote site's
content is not redesigned. WebView transport, cookies, navigation, stable
window policy, IME handling, and Back behavior remain separate from native
chat.

## Do's and Don'ts

- Use real full-window native captures at matching density, font scale, and
  orientation for before/after comparison; label synthetic content.
- Preserve actual 48dp targets, readable colors, system Back, focus, draft
  identity, and the full editor viewport at 200% font scale.
- Keep recovery, approval, stop, and uncertain outcomes explicit.
- Keep the normal reading canvas quiet; put detail behind real disclosures.
- Do not replace existing runtime, account, network, or storage contracts to
  simplify presentation.
- Do not treat green tests as a visual verdict, or use component crops as proof
  of whole-window behavior.
- Do not copy another product's logo, artwork, reference screenshots, claims,
  or non-existent capabilities into CopilotGo.
