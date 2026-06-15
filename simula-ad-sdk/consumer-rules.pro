# ── Simula Ad SDK — consumer R8/ProGuard rules ──────────────────────────────
# Packaged into the AAR and applied by the *consuming app's* R8. Keep this file
# as small as possible: every rule here blocks the consumer's R8 from
# shrinking/obfuscating part of the SDK. Pin ONLY what R8 cannot infer on its own.
#
# Deliberately NOT kept here (already handled — adding rules would only bloat):
#   • @Serializable DTOs (ad.simula.ad.sdk.network.*) — kotlinx-serialization
#     ships its own R8 rules (META-INF/com.android.tools/r8) that the consumer
#     app applies transitively; they cover every @Serializable class.
#   • SDK Activities — declared in AndroidManifest.xml, so R8/AAPT keep them.
#   • Public API (SimulaProvider, SimulaAds, MiniGame*, NativeAdSlot, …) — reached
#     from the consumer's own code, so R8 keeps exactly what each app uses and
#     strips the rest. Pinning it would defeat shrinking.

# WebView JS bridge: these methods are invoked by name from JavaScript, so R8
# cannot see the call sites and would strip/rename them. Scoped to the SDK
# package and gated on the annotation, so only the bridge methods are pinned.
-keepclassmembers class ad.simula.ad.sdk.** {
    @android.webkit.JavascriptInterface <methods>;
}
