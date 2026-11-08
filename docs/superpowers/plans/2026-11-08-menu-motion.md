# Syclop Menu Motion and Update Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace instant menu jumps with coordinated fluid transitions and prepare0.1.2 with version-range and patch-note update UI.

**Architecture:** Reuse Compose animation, existing sidebar state/focus handlers and Settings category routing. Keep animation state local to menus. Reuse AppUpdate.notes and the existing Markdown release-notes dialog; preserve download/security behavior.

**Tech Stack:** Kotlin, Jetpack Compose TV, Android API24+, existing JUnit/Compose tests.

**Spec:** docs/superpowers/specs/2026-11-08-menu-motion-design.md

## Global Constraints

- Preserve published0.1.1/f51 artifacts, original media-client working changes, frozen qualification and Shield state. Work only in /private/tmp/syclop-menu-motion-2026-11-08 and new UI evidence directories.
- Both sidebar styles: about240ms open/200ms close, one coordinated small leading-edge glide/fade, stable text size. Focus feedback about140ms and at most2% scale.
- Settings Classic/Zen/Horizon and nested menus: about180ms fade/16dp directional glide; Back reverses, RTL mirrors.
- No new dependencies/global animation-default changes, playback-navigation changes, blur-radius animation, private-service/account actions or publication.
- Preserve input/focus/Back/profile/current-route contracts; active pane alone is interactive; interruption/reversal works and system duration scale0 settles immediately.
- Version0.1.2/code3. Update notes come from existing release metadata; Later initially focused, Update now explicit, notes never trigger download.

## Review Focus

- Rapid open→close→open and repeated D-pad keys keep the latest state without losing focus.
- Outgoing Settings content cannot take focus while two panes coexist; nested Back restores the correct parent item.
- RTL, collapsed mode and blur off/on preserve geometry and leading-edge movement.
- System animation scale0 has no stranded visible/focusable layers or waits for animation completion.
- Long/blank notes remain readable and navigable; Back from notes restores the offer without downloading.

### Task1: Coordinated sidebar motion

**Files:** Modify app/src/main/java/com/nuvio/tv/MainActivity.kt and ModernSidebarBlurPanel.kt. A small internal sidebar-motion helper may be added under the same package if both production styles share it; no generic animation framework. Test app/src/androidTest/java/com/nuvio/tv/SidebarMotionTvTest.kt and relevant existing requester tests.

**Interfaces:** Existing LegacySidebarScaffold/ModernSidebarScaffold and ModernSidebarBlurPanel entry points keep their callers. The helper, if needed, owns a single interruptible progress value and is used by production, not a test-only adapter. No shared token-file edits.

- [ ] Add meaningful Compose tests for intermediate opening/reversal frames, leading-edge RTL movement, disabled-motion settled state and real focused-item navigation. A snap/cut or stale outgoing state must fail the assertions; do not test only constants.
- [ ] Run RED before implementation and retain the exact failure. Root coordinates Gradle/device commands; no concurrent Gradle builds.
- [ ] Coordinate legacy width/header/labels and modern panel/pill using the approved times. Remove compounded modern scale/opacity. Keep blur constant while its visible panel fades, using the existing fixed panel/GPU transform. Limit item scale to1.02.
- [ ] Run GREEN and existing DrawerFocusRequesterStateTest/Home navigation checks; inspect both styles and interruption/blur/RTL on the owned emulator.
- [ ] Self-review and commit only owned files; report commit range/test evidence/limits to root.

### Task2: Fluid Settings panes

**Files:** Modify app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsScreen.kt; a focused internal Settings transition component may be extracted beside it for both layout branches. Modify SettingsDesignSystem.kt only if focused visuals need the scoped140ms response. Test app/src/androidTest/java/com/nuvio/tv/ui/screens/settings/SettingsMotionTvTest.kt and existing SettingsStructureTest/SettingsCatalogTest.

**Interfaces:** SettingsScreen caller and Settings category/menu-return policies stay unchanged. Reuse one transition wrapper for Classic/Zen/Horizon and include integration nested subpages. Keep focus restoration/debounce semantics; outgoing content must not request focus.

- [ ] Add Compose regressions proving an intermediate frame exists, outgoing controls are not focusable, rapid category replacement settles on the latest pane, nested Back reverses/restores parent focus, and RTL/scale0 work. Use real production transition/component behavior.
- [ ] Run RED before implementation; coordinate build/device execution through root.
- [ ] Add local180ms fade/16dp glide to both layout branches and nested pages; use hierarchy to distinguish forward/Back rather than treating equal rail categories as forward. Preserve existing menu-return/rail behavior and couple incoming focus safely to the active pane.
- [ ] Run GREEN plus existing SettingsStructureTest/SettingsCatalogTest. Root records actual full-app visual/navigation verification in all three styles.
- [ ] Self-review and commit only owned files; report evidence and any limits.

### Task3: Version and patch notes in update offer (root)

**Files:** Modify app/src/full/java/com/nuvio/tv/updater/ui/UpdateBannerHost.kt, existing UpdateDialogs.kt if needed, app/src/main/res/values/strings.xml and app/build.gradle.kts. Add app/src/androidTest/java/com/nuvio/tv/updater/ui/UpdateOfferDetailsTvTest.kt. Add docs/releases/0.1.2.md with accurate implemented changes before final build.

**Interfaces:** Keep UpdateBannerHost/state/callback API. Read current BuildConfig.VERSION_NAME, available update.tag and update.notes; reuse UpdateReleaseNotesDialog for full notes. No repository/downloader/installer contract changes.

- [ ] Add failing UI tests for installed→available versions, visible notes/blank fallback, Later initial focus, long-notes scrolling/Back and download callback counts0 until explicit Update now.
- [ ] Show a bounded readable patch-note preview and a full-notes action with correct focus restoration. Use current strings for Later/Update now and add localized-format strings for version range; no hardcoded installed version.
- [ ] Set0.1.2/code3, write patch notes for the actual change set, run component tests plus existing updater consent/version/download-policy tests.
- [ ] Build app/test APKs, run full JVM suite and relevant lint, run owned-emulator UI/navigation fixtures, record a short motion preview and independent whole-change review. Fix concrete regressions, then provide a local preview APK/report. Public release and Shield installation stay out of scope.
