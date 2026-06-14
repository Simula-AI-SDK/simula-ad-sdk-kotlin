# ── Simula Ad SDK — library self-minification rules ─────────────────────────
# Applied ONLY if the library minifies itself, which it does not: release uses
# isMinifyEnabled = false (see build.gradle.kts). This file is NOT shipped to
# consumers — rules consumers need live in consumer-rules.pro.
#
# Intentionally empty. The previous rules were inert or harmful:
#   • -keep class com.simula.ad.sdk.{model,network}.** { *; } — wrong package
#     root (the real root is ad.simula.ad.sdk.*), so they matched nothing; and a
#     broad "{ *; }" keep would block shrinking if the path were corrected.
#   • -keep*class kotlinx.serialization.json.** … — redundant; kotlinx-serialization
#     bundles its own R8 rules.
