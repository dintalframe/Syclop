# GitHub Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Syclop GitHub landing page and README concise, modern, and useful to someone installing the app.

**Architecture:** Keep the existing static HTML/CSS, local mascot, and GitHub Pages workflow. Use latest-release URLs and existing documentation. No app or release behavior changes.

**Tech Stack:** HTML, CSS, Markdown, GitHub Pages.

**Spec:** docs/superpowers/specs/2026-11-08-github-page-design.md (user approved 2026-11-08).

## Global Constraints

- Preserve CSP, accessibility, GPLv3/upstream credit, local assets, and Android 7.0+ requirement.
- Add no dependencies, trackers, external fonts, autoplay, or hosting services.
- Link the actual latest release; do not call an unpublished version stable.

## Review Focus

- Narrow phones: no clipped actions or horizontal overflow.
- Keyboard users: every action has a visible focus indicator.
- New users: download and authorized-source requirement are clear.
- Returning users: release notes remain easy to reach.
- Developers: build/native provenance/license details remain linked.

### Task 1: Clean up the website and README

**Files:** README.md, docs/index.html, docs/style.css. Preserve docs/assets/syclop-primary-head.png.

**Interfaces:** Existing GitHub Pages workflow and /docs root; latest release https://github.com/dintalframe/Syclop/releases/latest. No JavaScript or application interface changes.

- [ ] Replace outdated source-only availability copy with the approved hero, download/releases actions, three brief benefits, setup steps, and small support/build/license footer.
- [ ] Shorten README to introduction, download, accurate setup, support and developer links, and attribution.
- [ ] Keep responsive typography/layout, visible keyboard focus, readable contrast, local assets, reduced-motion behavior and CSP.
- [ ] Inspect desktop and mobile browser renders; check local link targets and keyboard focus. No automated tests needed for this reversible content/style change.
- [ ] Obtain independent review, commit only owned presentation files, publish to existing GitHub Pages, and inspect the live page.
