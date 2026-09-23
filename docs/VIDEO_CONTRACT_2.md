# Video Contract 2

The imperative interstitial and rewarded load requests advertise:

```json
{"contracts":{"video":2}}
```

Sessions do not advertise a video capability. Contract behavior activates only when the response
root contains the exact JSON number `"video_contract": 2`; string values and the retired
`video_plan_version`/capability flags are ignored.

## Stitched Assets

`creative.url` is one stitched video. `creative.segments` contains telemetry attribution ranges:
`clip_index`, `video_pool`, `start_seconds`, and `end_seconds`. The SDK accepts at most three entries,
with indices starting at zero, finite positive contiguous ranges, and the first range starting at
zero. Invalid segment metadata is discarded without rejecting an otherwise usable video. Segments
never cause player replacement or fallback-screen handoff.

Video load readiness requires a complete local cache file. Downloads are single-flight, limited to
two concurrent transfers, 30 seconds total, 50 MiB per asset, and 100 MiB process-wide. Files use
opaque URL hashes and process-isolated directories. A persisted index, capped at 256 entries, records
each partial before its file is created and atomically publishes its completed state. Startup reads
only that bounded index and never enumerates the cache directory. MediaPlayer receives only an
index-complete local path; the SDK does not fall back to remote streaming. This cache format was not
shipped, so pre-index cache files are intentionally ignored and no migration is performed.

## Reward And End Screens

For rewarded contract-2 units with `ad_behavior.reward.earn_at: "unit_end"`, the primary gate or
video end permits progression but does not earn. Authority and earned state resolve at the final
renderable screen's gate or end. The publisher grant and durable verification occur once at
whole-unit close. If end screens are unusable, the last successfully rendered screen remains
authoritative; if no fallback rendered, the primary is authoritative. Fallback fetch failure and the
bounded post-close fetch timeout resolve that authority once and fail open to unit completion rather
than exposing or blocking the host app. Teardown does not salvage an unearned unit-end reward.
Verification uses `completion_reason: "unit_end"`.

Fallback ads remain ordinary end screens. They are not interpreted as consecutive primary clips.

## Measurement And Interaction

At the once-only two-second foreground impression commit, the SDK independently sends one bounded,
unauthenticated GET to a valid absolute HTTP(S) `impression_url`. It has no retry, authorization,
SDK, device, or privacy headers; failure does not affect callbacks or the durable `/seen` beacon.

Trusted HTML CTA gestures carry a bounded `interaction_id` and `click_source` through telemetry,
the durable click beacon, publisher callback, and routing. Native controls create UUIDs. Missing or
invalid HTML sources resolve by semantic slot to `primary_unknown`, `end_screen_1_unknown`, or
`end_screen_2_unknown`. One physical gesture is claimed once.

`video_duration` emits only at 50 percent. Segment attribution changes with playback position without
restarting MediaPlayer. Visible video keeps the screen awake only while foreground playback is active.

## Progress Bar

`ad_behavior.progress_bar.style` accepts `single` (default) or `two_tone`. Two-tone uses `#1186F2`
from zero to the effective close gate, `#1156B6` after the gate through video end, and `#3A3A40` for
the track. No gate, or a gate at/after duration, renders the played portion entirely bright. Progress
state resets for each screen.
