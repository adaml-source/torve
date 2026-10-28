# Older Fire OS ART rejects the optimized Compose default-argument bridge for
# this large browser (FocusRequester register reused as an integer). Keep its
# argument/default-mask handling intact; the rest of the app still optimizes.
-keepclassmembers,allowobfuscation class com.torve.android.tv.screens.TvSeeAllScreenKt {
    public static void TvSeeAllScreen(...);
}
