# Restart and playback controls

These controls are part of Syclop's development build. See the [project status](SYCLOP_FEATURE_AUDIT.md) for broader compatibility and release limits.

## Restart Syclop

Open **Settings → About → Restart Syclop**. The confirmation initially focuses **Cancel**. Press Right to select **Restart Syclop**, then press the remote's center button to confirm. Cancel or Back closes the confirmation and returns focus to the settings action.

Restart reopens Home with fresh activity and navigation state and refreshes startup initialization. It retains accounts, profiles, preferences, the library and viewing progress. It restarts the app's UI task; it does **not** reset the Android OS process, clear app data or reboot the TV.

## Start from beginning

A partially watched movie or episode shows **Start from beginning** beside **Resume** on its detail screen. Choose it to start that selected movie or episode at zero instead of using its saved resume position. For a show, this restarts the selected episode, not the entire series or every episode's progress.

During on-demand playback in the internal player, show the controls and choose the labelled **Start from beginning** action above the progress bar. It seeks the current media to zero and resumes playback. A delayed saved-progress lookup or queued save cannot replace that explicit choice with an older position or completion state. Starting over opens a new viewing session, allowing it to save resume progress even after the previous pass reached its completion threshold. Returning after another episode failed before playback also opens the correct session. An outgoing episode can still finish its own final save. The action does not delete watched history or clear the rest of the library.

The internal player shares progress ownership across controller instances. Only a foreground player can begin a new session; delayed saves cannot reclaim it after another player takes over. Ordinary pause/resume retains the same session.

The current-media action is unavailable for live playback. A title without resumable progress does not need a separate detail-screen start action; its ordinary Play action starts playback normally.

## Next Episode

During internal on-demand playback, **Next Episode** appears alongside **Start from beginning** when Syclop knows another eligible episode is available. Use Left/Right to focus it and the remote's center button to select it. It is hidden when there is no known next episode, the next episode has not aired, or a next-episode search/countdown is already active.

The action follows existing source-selection and next-episode autoplay preferences. With manual source selection and no next-episode auto-selection preference enabled, it opens the next episode's source picker. Where auto-selection is configured, it uses those preferences. Selecting Next Episode does not enable automatic playback or change those settings. A known next episode does not guarantee that a connected service will return a playable source.

## External players

The in-playback controls described above belong to Syclop's internal player. Just Player, VLC and other external players provide their own playback controls. Starting from the beginning before an external handoff requests position zero using the supported player's intent contract; the receiving player determines whether it honors that request. Do not infer external-player behavior from an internal-player check. External-player result persistence is outside the internal session authority. Ordering a delayed external return against a new internal rewatch still needs a handoff lease and acceptance checks.

External tracking now captures a generation with each pending handoff. A delayed duration lookup, queued progress save, monitor callback or auto-next continuation cannot clear or update a newer external tracking session, including when the same metadata object is reused. A valid current result can still save after normal tracking cleanup, and process recovery binds the saved record to one current generation. A new `startTracking` retires older continuations even if the new launch later fails; this is tracking-setup ownership, rather than proof that the external player accepted its intent.

This guard cannot undo a repository write or network scrobble that has already started. Durable internal/external ordering, correlating overlapping physical activity results, and account/profile-origin checks remain separate acceptance work. Synthetic tracker tests do not establish how a particular installed player returns progress.

## Verification status

The latest signed fullDebug app and AndroidTest APK builds completed successfully through the public bench build command. All **1,456 selected JVM tests in 199 suites** passed with zero failures, errors or skips. Twelve new tests call production external-tracker APIs with deferred duration lookups and mocked Android/persistence boundaries. They cover replacement sessions, same-object reuse, queued saves/scrobbles, cloud-token isolation, recovery/watchdog behavior, Zidoo cancellation and valid current backfill/completion. Three startup policy tests cover Home/owner gating.

Fifteen tests call the production internal playback controller with mocked engine/repository boundaries: seek/play from pause, new rewatch progress after completion, manual next-episode source selection, delayed normalization, already-entered persistence, outgoing-episode completion, stale lower progress, revisiting a video, a failed episode visit without progress, cross-controller rewatches, foreground lifecycle re-entry and unrelated profile saves. The suite also covers delayed-resume invalidation, initialization preservation and category row-order regressions.

On the earlier **API 34 Android TV emulator** acceptance build, all **11 targeted instrumentation tests passed** through the repeatable `playback-controls` command. These tests exercise the production detail actions, labelled internal-player actions, unavailable/final-episode states and restart confirmation focus/callbacks. All six fresh fixture screenshots were visually inspected: labels were not clipped, Cancel had initial confirmation focus, and Right moved focus to Restart Syclop. The latest tooling run passed **97 pure bench-tool tests**, including manifest/protocol checks, fixed suites, stale capture handling, invalid paths and sanitized failure reporting. See the [repeatable commands](tools/ANDROID_BENCH.md#playback-control-fixtures).

The device fixtures are account-free component/callback tests using synthetic media without credentials, playable URLs or source searches. The earlier `e7634c49c` production Settings → About flow was also exercised on the emulator: Cancel retained the current activity, confirmation created a fresh Home activity and refreshed startup initialization, and nine selected settings/history files remained byte-identical. This account-free check does not establish live account refresh behavior. No live Debrid playback, external-player handoff or decoder acceptance is established by these results, and compatibility with every Android TV is not claimed. The [bench guide](tools/ANDROID_BENCH.md) describes the isolated emulator and its verification boundaries.
