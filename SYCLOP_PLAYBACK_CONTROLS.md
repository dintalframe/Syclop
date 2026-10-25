# Restart and playback controls

These controls are part of Syclop's development build. See the [project status](SYCLOP_FEATURE_AUDIT.md) for broader compatibility and release limits.

## Restart Syclop

Open **Settings → About → Restart Syclop**. The confirmation initially focuses **Cancel**. Press Right to select **Restart Syclop**, then press the remote's center button to confirm. Cancel or Back closes the confirmation and returns focus to the settings action.

Restart reopens Home with fresh activity and navigation state and refreshes startup initialization. It retains accounts, profiles, preferences, the library and viewing progress. It restarts the app's UI task; it does **not** reset the Android OS process, clear app data or reboot the TV.

## Start from beginning

A partially watched movie or episode shows **Start from beginning** beside **Resume** on its detail screen. Choose it to start that selected movie or episode at zero instead of using its saved resume position. For a show, this restarts the selected episode, not the entire series or every episode's progress.

During on-demand playback in the internal player, show the controls and choose the labelled **Start from beginning** action above the progress bar. It seeks the current media to zero and resumes playback. A delayed saved-progress lookup cannot replace that explicit choice with an older position. Starting over also resets the current session's completion latch, allowing the new viewing pass to save resume progress even after the previous pass reached its completion threshold. The action does not delete watched history or clear the rest of the library.

The current-media action is unavailable for live playback. A title without resumable progress does not need a separate detail-screen start action; its ordinary Play action starts playback normally.

## Next Episode

During internal on-demand playback, **Next Episode** appears alongside **Start from beginning** when Syclop knows another eligible episode is available. Use Left/Right to focus it and the remote's center button to select it. It is hidden when there is no known next episode, the next episode has not aired, or a next-episode search/countdown is already active.

The action follows existing source-selection and next-episode autoplay preferences. With manual source selection and no next-episode auto-selection preference enabled, it opens the next episode's source picker. Where auto-selection is configured, it uses those preferences. Selecting Next Episode does not enable automatic playback or change those settings. A known next episode does not guarantee that a connected service will return a playable source.

## External players

The in-playback controls described above belong to Syclop's internal player. Just Player, VLC and other external players provide their own playback controls. Starting from the beginning before an external handoff requests position zero using the supported player's intent contract; the receiving player determines whether it honors that request. Do not infer external-player behavior from an internal-player check.

## Verification status

The signed fullDebug app and AndroidTest APK builds completed successfully through the public bench build command. All **1,386 selected JVM tests in 194 suites** passed with zero failures, errors or skips. Three new tests call the production playback controller with mocked engine/repository boundaries: exact seek/play from pause, saving new rewatch progress after completion, and opening manual next-episode source selection without changing the current media. The suite also covers delayed-resume invalidation, initialization preservation and category row-order regressions.

On the **API 34 Android TV emulator**, all **11 targeted instrumentation tests passed** through the repeatable `playback-controls` command. These tests exercise the production detail actions, labelled internal-player actions, unavailable/final-episode states and restart confirmation focus/callbacks. All six fresh fixture screenshots were visually inspected: labels were not clipped, Cancel had initial confirmation focus, and Right moved focus to Restart Syclop. All **82 pure bench-tool tests** passed, including manifest/protocol checks, stale capture handling, invalid paths and sanitized failure reporting. See the [repeatable commands](tools/ANDROID_BENCH.md#playback-control-fixtures).

The device fixtures are account-free component/callback tests using synthetic media without credentials, playable URLs or source searches. The earlier `e7634c49c` production Settings → About flow was also exercised on the emulator: Cancel retained the current activity, confirmation created a fresh Home activity and refreshed startup initialization, and nine selected settings/history files remained byte-identical. This account-free check does not establish live account refresh behavior. No live Debrid playback, external-player handoff or decoder acceptance is established by these results, and compatibility with every Android TV is not claimed. The [bench guide](tools/ANDROID_BENCH.md) describes the isolated emulator and its verification boundaries.
