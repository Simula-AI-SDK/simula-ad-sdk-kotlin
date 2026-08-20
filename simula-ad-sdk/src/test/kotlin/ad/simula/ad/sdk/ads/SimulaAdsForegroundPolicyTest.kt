package ad.simula.ad.sdk.ads

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulaAdsForegroundPolicyTest {
    @Test
    fun `only foreground process importance seeds WebView retention`() {
        assertTrue(
            isForegroundProcessImportance(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ),
        )
        assertFalse(
            isForegroundProcessImportance(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ),
        )
        assertFalse(
            isForegroundProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE),
        )
        assertFalse(
            isForegroundProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE),
        )
        assertFalse(
            isForegroundProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED),
        )
    }
}
