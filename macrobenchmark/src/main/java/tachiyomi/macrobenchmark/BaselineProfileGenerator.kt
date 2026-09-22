package tachiyomi.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

private const val TARGET_PACKAGE = "app.yomikku.benchmark"
private const val WAIT_MILLIS = 10_000L

/**
 * Regenerates `app/src/main/baseline-prof.txt`.
 *
 * Run on a device where the benchmark build has already been through setup:
 *
 *     ./gradlew -Pabis=<device abi> :macrobenchmark:connectedBenchmarkAndroidTest
 *
 * then copy the generated profile over `app/src/main/baseline-prof.txt`.
 *
 * A fresh install parks on onboarding's storage step, which waits on the system document picker
 * and cannot be driven from here - so on an unconfigured device every selector below finds nothing
 * and the profile only covers the wizard. Complete setup once by hand first.
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        profileBlock = {
            pressHome()
            startActivityAndWait()

            // Every step is optional. These used to be bare findObject().click() calls, so a
            // single missing element threw an NPE and lost the whole run - a profile covering
            // three tabs of four still beats no profile at all.
            tap(By.text("Updates"))
            tap(By.text("History"))
            tap(By.text("Browse"))
            tap(By.text("Library"))

            if (tap(By.text("More"))) {
                tap(By.text("Settings"))
                device.pressBack()
            }
        },
    )

    private fun MacrobenchmarkScope.tap(selector: BySelector): Boolean {
        val target = device.wait(Until.findObject(selector), WAIT_MILLIS) ?: return false
        target.click()
        device.waitForIdle(WAIT_MILLIS)
        return true
    }
}
