package com.nuvio.app.features.lights

import com.nuvio.app.testing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LinuxWebhookLightsTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxWebhookLightsProbe::class.java, case) }
    @Test fun postPlaybackLifecycleUsesOrderedJsonEvents() = probe("post")
    @Test fun getPlaybackLifecycleUsesEmptyBodies() = probe("get")
    @Test fun repeatedPauseDoesNotDeliverTwice() = probe("duplicate")
    @Test fun otherPlaybackSourceCannotPauseOrEndSession() = probe("wrongSource")
    @Test fun excludedExternalPlaybackDoesNotAcquireSession() = probe("externalOff")
    @Test fun disabledPlaybackDoesNotAcquireSession() = probe("disabled")
    @Test fun testActionDeliversStartAndEndAndCompletes() = probe("test")
    @Test fun failedDeliveryDoesNotPoisonWorker() = probe("failure")
    @Test fun disablingActiveSessionStillDeliversEnd() = probe("disableActive")
}

internal object LinuxWebhookLightsProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val scenario = args[1]
        LocalHttpFixture().use { server ->
            server.route("/") { exchange, request ->
                exchange.respond(if (scenario == "failure" && request.path == "/start") 503 else 200,
                    "fixture".toByteArray())
            }
            server.start()
            val settings = LightsSettingsRepository
            settings.setScheduleEnabled(false)
            settings.setGoveeApiKey("")
            settings.setWebhookStartUrl("${server.url}/start")
            settings.setWebhookPauseUrl("${server.url}/pause")
            settings.setWebhookResumeUrl("${server.url}/resume")
            settings.setWebhookEndUrl("${server.url}/end")
            settings.setWebhookMethod(if (scenario == "get") LightsWebhookMethod.Get else LightsWebhookMethod.Post)
            settings.setExternalPlayers(scenario != "externalOff")
            settings.setEnabled(scenario != "disabled")
            assertFalse(settings.snapshot().hasGovee)
            fun expect(event: String) {
                val request = server.nextRequest()
                assertEquals("/$event", request.path)
                if (scenario == "get") {
                    assertEquals("GET", request.method)
                    assertEquals("", request.body)
                } else {
                    assertEquals("POST", request.method)
                    assertTrue(request.headers["content-type"].orEmpty().single().startsWith("application/json"))
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    assertEquals("nuvio", body["source"]?.jsonPrimitive?.content)
                    assertEquals(event, body["event"]?.jsonPrimitive?.content)
                }
            }
            if (scenario in setOf("test", "disabled", "externalOff")) {
                if (scenario != "test") LightsController.playing(
                    if (scenario == "externalOff") LightsPlaybackSource.External else LightsPlaybackSource.Player)
                // Test is an observable worker barrier. An incorrectly acquired playback session
                // would reject this command instead of producing the expected two requests/Done.
                LightsController.runTest()
                expect("start")
                expect("end")
                withTimeout(12_000) { settings.uiState.first { it.testState == LightsTestState.Done } }
            } else {
                LightsController.playing(LightsPlaybackSource.Player)
                expect("start")
                if (scenario == "disableActive") {
                    settings.setEnabled(false)
                } else {
                    if (scenario == "wrongSource") {
                        LightsController.paused(LightsPlaybackSource.External)
                        LightsController.ended(LightsPlaybackSource.External)
                    }
                    LightsController.paused(LightsPlaybackSource.Player)
                    if (scenario == "duplicate") LightsController.paused(LightsPlaybackSource.Player)
                    expect("pause")
                    LightsController.playing(LightsPlaybackSource.Player)
                    expect("resume")
                }
                LightsController.ended(LightsPlaybackSource.Player)
                expect("end")
                // Await completion of the End via a following observable command. While the
                // previous HTTP response unwinds, the worker retains ownership of its session.
                LightsController.runTest()
                expect("start")
                expect("end")
                withTimeout(12_000) { settings.uiState.first { it.testState == LightsTestState.Done } }
            }
            assertTrue(server.drainRequests().isEmpty(), "Unexpected duplicate or out-of-source delivery")
        }
    }
}
