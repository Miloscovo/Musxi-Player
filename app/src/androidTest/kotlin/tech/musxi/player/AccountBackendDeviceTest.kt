package tech.musxi.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.accounts.NeteaseWebLogin
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.Platform

/** Explicit opt-in. No logout, official-app launch, credential submission, playback or platform writes. */
@RunWith(AndroidJUnit4::class)
class AccountBackendDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MusxiApplication
    private fun main(work: () -> Unit) = instrument.runOnMainSync(work)
    private fun waitFor(timeout: Long = 60_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue("Timed out waiting for account operation", condition())
    }

    /** Host starts this process offline, then restores the original network after offline_ready. */
    @Test fun offlineColdStartRecoversSavedAccountsOnValidatedNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowNetworkRecovery") == "true")
        val report = JSONObject().put("startedAtEpochMs", System.currentTimeMillis())
        val output = File(app.filesDir, "account-network-device-20261124.json")
        var activity: MainActivity? = null
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor {
                main { activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
                activity != null
            }
            main { activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitFor { Platform.entries.all {
                val state = app.accounts.states.value.getValue(it)
                state.phase == AccountPhase.RETRYABLE_ERROR && state.error == AccountError.NETWORK
            } }
            val originals = app.accounts.states.value.toMap()
            val rows = JSONArray()
            Platform.entries.forEach { platform ->
                val state = originals.getValue(platform)
                assertNotNull("Saved identity required for $platform", state.profile)
                val facts = app.accounts.verification.value.getValue(platform)
                assertEquals(AccountError.NETWORK, facts.lastFailure)
                assertNotNull(facts.lastFailureAtEpochMs)
                rows.put(JSONObject().put("platform", platform.name)
                    .put("offlinePhase", state.phase.name).put("offlineError", state.error?.name)
                    .put("failureAtEpochMs", facts.lastFailureAtEpochMs).put("offlineGeneration", state.generation))
            }
            report.put("platforms", rows).put("stage", "offline_ready")
                .put("offlineObservedAtEpochMs", System.currentTimeMillis())
            output.writeText(report.toString(2))
            // Observe production callbacks and cooldown; do not call restore or refresh manually.
            waitFor(180_000L) { Platform.entries.all {
                app.accounts.states.value.getValue(it).phase == AccountPhase.CONNECTED
            } }
            Platform.entries.forEachIndexed { index, platform ->
                val state = app.accounts.states.value.getValue(platform)
                val facts = app.accounts.verification.value.getValue(platform)
                assertEquals(originals.getValue(platform).profile?.id, state.profile?.id)
                assertTrue(state.generation > originals.getValue(platform).generation)
                assertNotNull(facts.lastVerifiedAtEpochMs)
                assertNull(facts.lastFailure)
                rows.getJSONObject(index).put("recoveredPhase", state.phase.name)
                    .put("recoveredGeneration", state.generation).put("retainsIdentity", true)
                    .put("verifiedAtEpochMs", facts.lastVerifiedAtEpochMs)
            }
            report.put("stage", "recovered").put("result", "passed")
        } catch (failure: Throwable) {
            report.put("result", "failed").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            main { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            report.put("finalPhases", JSONObject().apply { Platform.entries.forEach {
                put(it.name, app.accounts.states.value.getValue(it).phase.name)
            } })
            report.put("finishedAtEpochMs", System.currentTimeMillis())
            output.writeText(report.toString(2))
        }
    }

    @Test fun savedAccountsReverifyAndCancelledCandidatesPreserveOwners() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowAccountReadOnly") == "true")
        val report = JSONObject().put("startedAtEpochMs", System.currentTimeMillis()).put("platforms", JSONArray())
        val output = File(app.filesDir, "account-backend-device-20261124.json")
        var browser: NeteaseWebLogin? = null
        var candidatePlatform: Platform? = null
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor { app.accounts.states.value.values.none { it.phase == AccountPhase.REVALIDATING } }
            Platform.entries.forEach { platform ->
                val before = app.accounts.states.value.getValue(platform)
                val row = JSONObject().put("platform", platform.name).put("initialPhase", before.phase.name)
                report.getJSONArray("platforms").put(row)
                assertEquals("Saved account required for $platform", AccountPhase.CONNECTED, before.phase)
                val owner = requireNotNull(app.accounts.playbackIdentity(platform)).owner
                row.put("coldVerified", app.accounts.verification.value.getValue(platform).lastVerifiedAtEpochMs != null)
                val callbacks = java.util.concurrent.atomic.AtomicInteger()
                val failures = java.util.concurrent.atomic.AtomicInteger()
                main { repeat(3) {
                    app.accounts.refreshProfile(platform) { result ->
                        if (result.isFailure) failures.incrementAndGet()
                        callbacks.incrementAndGet()
                    }
                } }
                waitFor { callbacks.get() == 3 }
                row.put("refreshCallbacks", callbacks.get()).put("refreshFailures", failures.get())
                assertEquals(0, failures.get())
                assertEquals(before.generation, app.accounts.states.value.getValue(platform).generation)
                assertEquals(owner, app.accounts.playbackIdentity(platform)?.owner)
                if (platform != Platform.NETEASE) {
                    val ready = AtomicBoolean()
                    candidatePlatform = platform
                    main { app.accounts.reauthenticateOfficial(platform) {
                        // Inspect only the prepared intent; never launch another app or accept authorization.
                        row.put("officialIntentPrepared", it.`package` != null)
                        app.accounts.cancel(platform)
                        ready.set(true)
                    } }
                    waitFor { ready.get() }
                    candidatePlatform = null
                    val after = app.accounts.states.value.getValue(platform)
                    assertEquals(AccountPhase.CONNECTED, after.phase)
                    assertEquals(before.profile?.id, after.profile?.id)
                    assertTrue(after.generation > before.generation)
                    assertEquals(owner, app.accounts.playbackIdentity(platform)?.owner)
                    row.put("cancelRetainsOwner", true)
                } else {
                    var activity: MainActivity? = null
                    main { activity = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
                    assertNotNull(activity)
                    val environments = JSONArray()
                    repeat(2) { round ->
                        val checked = AtomicBoolean()
                        val closed = AtomicBoolean()
                        candidatePlatform = platform
                        main {
                            browser = NeteaseWebLogin.reauthenticate(requireNotNull(activity), app.accounts,
                                environmentChecked = { environments.put(it); checked.set(true) },
                                closed = { closed.set(true) })
                            assertNotNull(browser)
                        }
                        waitFor(30_000L) { checked.get() || closed.get() }
                        assertTrue("Browser environment not ready", checked.get())
                        main { browser?.close(); browser = null }
                        waitFor { closed.get() }
                        candidatePlatform = null
                        val after = app.accounts.states.value.getValue(platform)
                        assertEquals(AccountPhase.CONNECTED, after.phase)
                        assertEquals(before.profile?.id, after.profile?.id)
                        assertEquals(owner, app.accounts.playbackIdentity(platform)?.owner)
                        row.put("browserRounds", round + 1)
                    }
                    row.put("cookieEnvironmentChecks", environments).put("cancelRetainsOwner", true)
                }
                val catalog = app.playlists.catalog(platform)
                main { catalog.open(); catalog.refresh() }
                waitFor { !catalog.state.value.loading }
                row.put("catalogCount", catalog.state.value.items.size)
                    .put("catalogError", catalog.state.value.error ?: JSONObject.NULL)
                assertNull("Catalog failed for $platform", catalog.state.value.error)
                assertTrue("Expected saved playlists for $platform", catalog.state.value.items.isNotEmpty())
                row.put("result", "passed")
                output.writeText(report.toString(2))
            }
            report.put("result", "passed")
        } catch (failure: Throwable) {
            report.put("result", "failed").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            main { browser?.close(); candidatePlatform?.let { app.accounts.cancel(it) } }
            report.put("finishedAtEpochMs", System.currentTimeMillis())
            output.writeText(report.toString(2))
        }
    }
}
