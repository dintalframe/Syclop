# Syclop menu motion polish — proposed design

Make menus feel fluid and composed with the TV remote, using the existing visual style. Start from released source f51f8a033d0aa692f5a4f3d6df0c4793ebc9ada1 in a separate checkout; preserve the published0.1.1 artifacts, original working changes and Shield state.

## Approaches

- Timing-only tuning: smallest edit, but leaves default Settings panels switching instantly and the modern drawer’s layered animation intact.
- Coordinated menu polish (recommended): consistent motion for both sidebar styles, focused items and Settings panels, without a layout redesign.
- Cinematic motion: larger movement and heavier effects; more rendering cost and a longer perceived wait on a TV remote.

Approved by the user on2026-11-08: “Build this design.” The user confirmed the problem is instant jumps and wants smoother, more fluid movement.

## Proposed behavior

| Interaction | Proposed appearance |
| --- | --- |
| Open/close sidebar | About240ms opening/200ms closing. One coordinated fade and small edge glide; stable text size, minimal scaling. Panel, labels and collapsed pill hand off together. |
| Move menu focus | About140ms color/highlight response and at most2% scale. Clear selection with no bouncing or queued movements. |
| Change Settings category or open a nested menu | About180ms fade with a small16dp directional glide. Back reverses the direction; RTL mirrors horizontal motion. Apply consistently to Classic, Zen and Horizon. |

In the legacy drawer, coordinate panel width/header/label visibility rather than abruptly replacing them. In the modern drawer, remove the duplicate scale/opacity layers and preserve a consistent blur appearance while the panel remains visible. Keep the existing fixed-size/GPU-transform approach; do not animate blur radius or add dependencies.

Focus moves immediately with remote input. Interrupted/reversed animations continue from their current position. Only the active Settings pane accepts focus; outgoing panes cannot steal it. Existing Back, route selection, profile switching, focus recovery, collapsed-sidebar preferences and saved settings keep their behavior. Respect the system animation scale, including disabled animations. Global playback transitions and shared animation defaults are outside this pass.

## Verification

Exercise both drawer styles, blur on/off, collapsed mode, Settings category/nested Back navigation, rapid open-close-open and repeated D-pad movement. Check RTL and system animation scale0/1. Use a focused Android TV emulator fixture and a short recording for visual review; run relevant focus/navigation tests and build checks. Do not claim physical-device frame-rate improvement from emulator results.

The approved design is committed in an isolated checkout. The released source, original working tree, published0.1.1 assets and Shield remain untouched.

## Update-dialog addition requested by user

Prepare version0.1.2/code3. The update offer shows the installed version and available version, such as0.1.1→0.1.2, and a readable patch-notes preview from the existing GitHub release notes. Reuse the existing release-notes dialog for the full changelog. Keep Later initially focused and explicit Update now consent; opening/closing notes cannot start a download. Support long/empty notes and remote scrolling. This UI becomes available after installing0.1.2; existing0.1.1 is unchanged. No public publication is part of this implementation.
