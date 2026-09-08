# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The project owner and people using their own GitHub Copilot access to read and
compose conversations on an Android device. Conversations include Chinese text,
code, mathematics, and attachments.

## Product Purpose

CopilotGo is a native mobile conversation client. A successful interaction lets
the user find a conversation, read it comfortably, and submit an intentional
request without losing a draft or obscuring the state of ongoing work.

## Positioning

The native client uses the user's GitHub Copilot subscription. A separate Remote
WebView opens GitHub's remote experience. Optional, explicitly selected Agent mode
uses real search, page-reading, and MCP tools; it does not simulate tool results.

## Operating Context

The application is Chinese-first, built with Kotlin, Jetpack Compose, and
Material 3 for Android 12 and later. Users switch between reading long responses,
editing multiline drafts, adding files or images, inspecting tool activity, and
returning to historical conversations. Work can continue while navigating away
from a conversation.

## Capabilities and Constraints

- Preserve existing ordinary chat, supported image input, Markdown/code/math,
  file library, history, model selection, and system speech-to-text entry.
- Preserve explicit Agent mode, exact approval and source identity, cancellation,
  uncertain-result protection, and private-credential isolation.
- Preserve existing drafts, attachments, conversation data, signing identity,
  and in-place update compatibility.
- Agent application HTTP/CONNECT/SOCKS proxy support is deliberately deferred.
  Direct access or a suitable system VPN is supported, with explicit failures
  rather than silent proxy bypass.
- Remote has a separate WebView transport, cookie, window, and IME lifecycle.
- This redesign does not add voice conversations, image generation, cloud sync,
  new accounts, or new models.

## Brand Commitments

The application name and identity remain CopilotGo. The user selected a familiar,
ChatGPT-inspired native composition: restrained neutral color, subtle borders,
and immersive conversation reading. Reference the craft and hierarchy, not
OpenAI's logo, content, screenshot assets, or claims of affiliation.

## Evidence on Hand

The confirmed Phase 3 product record and approved visual contract follow accepted
main `b737454ced3a62ec20d3e74fad46f70e3f33a175` and the delivered v0.3.0 checkpoint.
Production source, synthetic Android fixtures, and actual release acceptance
reports are the product evidence. Official ChatGPT Android store images are
private design references, not distributable application assets. No customer,
testimonial, commercial, or performance claims are supplied.

## Product Principles

1. Conversations and the user's composing task lead; controls support them.
2. Make actual state, source identity, limits, and recovery paths understandable.
3. Preserve intent: rejected requests retain drafts, and uncertain work is not
   silently replayed.
4. Keep private credentials and existing conversation data safe across navigation
   and updates.

## Accessibility & Inclusion

Use Android semantics, meaningful TalkBack names, and at least 48 dp interactive
targets. Support 200% font scaling, CJK, emoji, long names, light and dark modes,
and small or short-wide windows. Keep the whole editor above the real keyboard,
honor manual scrolling and system Back, and retain visible, actionable approval
and recovery states. Body and placeholder text require at least 4.5:1 contrast.
