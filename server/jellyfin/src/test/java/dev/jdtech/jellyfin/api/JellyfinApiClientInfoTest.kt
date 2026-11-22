package dev.jdtech.jellyfin.api

import android.app.Application
import android.provider.Settings
import dev.jdtech.jellyfin.server.jellyfin.BuildConfig
import kotlin.test.assertEquals
import org.jellyfin.sdk.android.androidDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CE (#386): the server is told the version of the installed app (phone or TV, with the beta's or
 * staging build's suffix), the one Settings > About shows, not upstream's version from `buildSrc`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinApiClientInfoTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
    }

    private fun installedVersion(versionName: String?) {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .versionName = versionName
    }

    @Test
    fun `the client version is the installed app's version name`() {
        installedVersion("2.4.1-beta.7")

        val clientInfo = JellyfinApi(context).api.clientInfo

        assertEquals("2.4.1-beta.7", clientInfo.version)
    }

    @Test
    fun `the client name is still the app label`() {
        installedVersion("2.4.0")

        val clientInfo = JellyfinApi(context).api.clientInfo

        assertEquals(
            context.applicationInfo.loadLabel(context.packageManager).toString(),
            clientInfo.name,
        )
    }

    @Test
    fun `a package without a version name sends unknown`() {
        installedVersion(null)

        assertEquals("unknown", JellyfinApi(context).api.clientInfo.version)
    }

    /**
     * #758: the device id has the build type suffix it had while the client lived in `data`, read
     * from this module's own BuildConfig: the plain id for release, `-staging` for the beta,
     * `-debug` for a debug build. A wrong one signs every user out on update, or lets the beta log
     * out the release app. The unit tests run for the debug build type only; the expected ids of
     * the other two say what their BuildConfig must give.
     */
    @Test
    fun `the device id carries the build type unless it is release`() {
        val plain = androidDevice(context).id
        val expected =
            mapOf("debug" to "$plain-debug", "staging" to "$plain-staging", "release" to plain)

        assertEquals(
            expected.getValue(BuildConfig.BUILD_TYPE),
            JellyfinApi(context).jellyfin.deviceInfo?.id,
        )
    }
}
