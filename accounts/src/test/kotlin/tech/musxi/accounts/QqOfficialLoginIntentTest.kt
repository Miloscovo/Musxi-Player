package tech.musxi.accounts

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class QqOfficialLoginIntentTest {
    @Test fun qqAuthorizationHasOwnTaskAndPackageSpecificRoundTripCallback() {
        val payload = "fixture:+/&=中文"
        for (scheme in listOf("tech.musxi.player.login", "tech.musxi.player.dev.login")) {
            val intent = QqDriver.officialIntent(payload, scheme)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("com.tencent.mobileqq", intent.`package`)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
            assertEquals("wtloginmqq", intent.data?.scheme)
            assertEquals("/qlogin", intent.data?.path)
            assertEquals(payload, intent.data?.getQueryParameter("qrcode"))
            assertEquals("$scheme://login-return", intent.data?.getQueryParameter("schemacallback"))
        }
    }
}
