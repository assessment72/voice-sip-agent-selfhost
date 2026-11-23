package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallBridgeTest {
    private val cell = FakeEndpoint("cellular")
    private val sip = FakeEndpoint("sip")
    private lateinit var bridge: CallBridge

    private fun build(config: GatewayConfig = GatewayConfig(cellularInboundTarget = "302")) {
        bridge = CallBridge(
            sideA = cell,
            sideB = sip,
            routeAtoB = { config.cellularInboundTarget },
            routeBtoA = { it.target },
            config = config,
            dialPolicyForA = { n -> if (n == "911") HangupCause.FORBIDDEN else null },
        )
        bridge.start()
    }

    @Before fun setUp() = build()
    @After fun tearDown() = bridge.stop()

    /** Waits for the bridge thread to drain, including tasks posted by the tasks it ran. */
    private fun settle() = repeat(3) { bridge.drain() }


    @Test
    fun hostedCallWaitsForCellularAnswerBeforeCreatingSipLegAndBridgesBothWays() {
        assertTrue(bridge.originateCellularThenSip("+967777405578", "sip:linphone@example.test"))
        settle()
        val cellularLeg = cell.dialed.single()
        assertEquals("+967777405578", cellularLeg.target)
        assertTrue("SIP leg must not start while cellular is ringing", sip.dialed.isEmpty())

        cellularLeg.remoteAnswered()
        settle()
        val sipLeg = sip.dialed.single()
        assertEquals("sip:linphone@example.test", sipLeg.target)

        sipLeg.remoteAnswered()
        settle()
        val cellPort = FakePort(isClockSource = true, fill = 11)
        val sipPort = FakePort(isClockSource = false, fill = 22)
        cellularLeg.mediaUp(cellPort)
        sipLeg.mediaUp(sipPort)
        settle()
        assertNotNull(bridge.session?.media)
        cellPort.clock!!.onFrame()
        assertEquals(11.toShort(), sipPort.written.single()[0])
        assertEquals(22.toShort(), cellPort.written.single()[0])

        cellularLeg.remoteEnded(HangupCause.NORMAL)
        settle()
        assertEquals(LegState.ENDED, sipLeg.state)
        assertNull(bridge.session)
    }

    @Test
    fun hostedOriginateReservationPreventsQueuedInboundCallFromStealingBridge() {
        val incoming = cell.incoming("13800001234")
        assertTrue(bridge.originateCellularThenSip("+967777405578", "sip:linphone@example.test"))
        settle()
        assertEquals(HangupCause.BUSY, incoming.endCause)
        assertEquals("+967777405578", cell.dialed.single().target)
        assertTrue("the rejected cellular inbound leg must not create a SIP leg", sip.dialed.isEmpty())
        assertEquals("hosted-outbound", bridge.session?.origin)
    }

    @Test
    fun oldHostedDurationGuardCannotEndANewerBridgeSession() {
        var hostedSessionId: Int? = null
        assertTrue(
            bridge.originateCellularThenSip("+967777405578", "sip:linphone@example.test") {
                hostedSessionId = it
            }
        )
        settle()
        val hostedCellularLeg = cell.dialed.single()
        val oldId = hostedSessionId ?: error("hosted session callback was not invoked")
        hostedCellularLeg.remoteEnded(HangupCause.NO_ANSWER)
        settle()
        assertNull(bridge.session)

        val laterInbound = cell.incoming("13800009999")
        settle()
        val laterSessionId = bridge.session?.id ?: error("later inbound session was not created")
        assertTrue(laterSessionId != oldId)

        bridge.endSession(oldId, HangupCause.NORMAL)
        settle()
        assertEquals("the stale timer must not terminate the newer call", laterSessionId, bridge.session?.id)
        assertTrue("new inbound leg remains active", laterInbound.state != LegState.ENDED)
    }

    @Test
    fun hostedCallCellularNoAnswerNeverCreatesSipLeg() {
        assertTrue(bridge.originateCellularThenSip("+967777405578", "sip:linphone@example.test"))
        settle()
        val cellularLeg = cell.dialed.single()
        cellularLeg.remoteEnded(HangupCause.NO_ANSWER)
        settle()
        assertTrue(sip.dialed.isEmpty())
        assertNull(bridge.session)
    }

    @Test
    fun cellularInboundToSipFullCall() {
        val a = cell.incoming("13800001234")
        settle()
        val b = sip.dialed.single()
        assertEquals("302", b.target)
        b.remoteRinging(); settle()
        assertTrue("cellular must not be answered before SIP", "answer" !in a.actions)
        b.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)

        val cellPort = FakePort(isClockSource = true, fill = 11)
        val sipPort = FakePort(isClockSource = false, fill = 22)
        a.mediaUp(cellPort); b.mediaUp(sipPort); settle()
        assertNotNull(bridge.session?.media)
        cellPort.clock!!.onFrame()
        assertEquals(11.toShort(), sipPort.written.single()[0])
        assertEquals(22.toShort(), cellPort.written.single()[0])

        b.remoteDtmf('5'); settle()
        assertEquals(listOf('5'), a.dtmf)

        b.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals(LegState.ENDED, a.state)
        assertNull(bridge.session)
        assertNull("clock detached", cellPort.clock)
    }

    @Test
    fun sipFailureRejectsCellular() {
        val a = cell.incoming("13800001234")
        settle()
        sip.dialed.single().remoteEnded(HangupCause.BUSY); settle()
        assertEquals(HangupCause.BUSY, a.endCause)
    }

    @Test
    fun cellularCallerHangsUpBeforeAnswerCancelsSip() {
        val a = cell.incoming("13800001234")
        settle()
        a.remoteEnded(HangupCause.NORMAL); settle()
        assertEquals("hangup:CANCELLED", sip.dialed.single().actions.last())
    }

    @Test
    fun sipInboundDialsCellularAndRingsBack() {
        val a = sip.incoming("302", target = "13800005678")
        settle()
        val b = cell.dialed.single()
        assertEquals("13800005678", b.target)
        assertTrue("180 sent while dialing", "ring" in a.actions)
        b.remoteAnswered(); settle()
        assertEquals(LegState.CONNECTED, a.state)
        a.remoteDtmf('9'); settle()
        assertEquals(listOf('9'), b.dtmf)
    }

    @Test fun mediaArrivingBeforeAnswerConfirmationDoesNotAnswerAgain() {
        val inbound = sip.incoming("302", target = "13800005678")
        inbound.autoConfirmAnswer = false
        settle()
        val outbound = cell.dialed.single()
        outbound.remoteAnswered(); settle()
        assertEquals(LegState.RINGING, inbound.state)
        assertEquals(1, inbound.actions.count { it == "answer" })

        // The cellular tap comes up while the SIP 200 response still awaits confirmation.
        outbound.mediaUp(FakePort(isClockSource = true, fill = 11)); settle()
        assertEquals(1, inbound.actions.count { it == "answer" })
        assertNull(bridge.session?.media)

        inbound.remoteAnswered()
        inbound.mediaUp(FakePort(isClockSource = false, fill = 22)); settle()
        assertNotNull(bridge.session?.media)
        assertEquals(1, inbound.actions.count { it == "answer" })
    }

    @Test
    fun normalizedYemeniE164TargetIsPassedToCellularEndpoint() {
        val inbound = sip.incoming("302", target = "+967777405578")
        settle()
        assertEquals("+967777405578", cell.dialed.single().target)
        val outbound = cell.dialed.single()
        outbound.remoteAnswered()
        settle()
        assertEquals(LegState.CONNECTED, inbound.state)
    }

    @Test
    fun cellularBusyMapsToSip() {
        cell.onDial = { it.remoteEnded(HangupCause.BUSY) }
        val a = sip.incoming("302", target = "13800005678")
        settle()
        assertEquals(HangupCause.BUSY, a.endCause)
        assertNull(bridge.session)
    }

    @Test
    fun emergencyNumbersAreNeverDialed() {
        val a = sip.incoming("302", target = "911")
        settle()
        assertTrue(cell.dialed.isEmpty())
        assertEquals(HangupCause.FORBIDDEN, a.endCause)
    }

    @Test
    fun prefixAllowList() {
        bridge.stop(); build(GatewayConfig(allowedDialPrefixes = listOf("1")))
        val a = sip.incoming("302", target = "8613800005678")
        settle()
        assertTrue(cell.dialed.isEmpty())
        assertEquals(HangupCause.FORBIDDEN, a.endCause)
    }

    @Test
    fun yemeniNationalPrefixAllowListMatchesNormalizedInternationalTarget() {
        bridge.stop()
        build(GatewayConfig(allowedDialPrefixes = listOf("77")))
        sip.incoming("caller", target = "+967777405578")
        settle()
        assertEquals("+967777405578", cell.dialed.single().target)
        assertTrue("SIP inbound leg remains owned by the bridge", bridge.session != null)
    }

    @Test
    fun yemeniInternationalPrefixAllowListMatchesNormalizedTarget() {
        bridge.stop()
        build(GatewayConfig(allowedDialPrefixes = listOf("+96777")))
        sip.incoming("caller", target = "+967777405579")
        settle()
        assertEquals("+967777405579", cell.dialed.single().target)
    }

    @Test
    fun secondCallWhileBusy() {
        cell.incoming("13800001234")
        settle()
        val second = sip.incoming("302", target = "13800005678")
        settle()
        assertEquals(HangupCause.BUSY, second.endCause)

        bridge.stop(); build(GatewayConfig(busyPolicy = BusyPolicy.IGNORE))
        sip.incoming("302", target = "13800005678"); settle()
        val ignored = cell.incoming("13800009999"); settle()
        assertEquals("native phone keeps it", LegState.PROCEEDING, ignored.state)
    }

    @Test
    fun answerTimeoutEndsBothLegs() {
        bridge.stop(); build(GatewayConfig(cellularInboundTarget = "302", answerTimeoutMs = 50))
        val a = cell.incoming("13800001234")
        Thread.sleep(150); settle()
        assertEquals(HangupCause.NO_ANSWER, a.endCause)
        assertEquals(LegState.ENDED, sip.dialed.single().state)
    }

    @Test
    fun loopbackEchoesCapturedAudio() {
        for (format in listOf(PcmFormat.NARROWBAND, PcmFormat.WIDEBAND)) {
            val port = FakePort(isClockSource = true, fill = 7, format = format)
            val mb = MediaBridge.loopback(port).also { it.start() }
            try {
                port.clock!!.onFrame()
                assertEquals(format.frameSamples, port.written.single().size)
                assertTrue(port.written.single().all { it == 0.toShort() })
                port.clock!!.onFrame()
                assertTrue(port.written.last().all { it == 7.toShort() })
            } finally { mb.stop() }
        }
    }

    @Test fun widebandCallBridgesWholeFramesInBothDirections() {
        val a = cell.incoming("13800001234"); settle()
        val b = sip.dialed.single()
        b.remoteAnswered(); settle()
        val cellPort = FakePort(true, 11, PcmFormat.WIDEBAND)
        val sipPort = FakePort(false, 22, PcmFormat.WIDEBAND)
        a.mediaUp(cellPort); b.mediaUp(sipPort); settle()
        cellPort.clock!!.onFrame()
        assertEquals(320, cellPort.written.single().size)
        assertEquals(320, sipPort.written.single().size)
        assertTrue(cellPort.written.single().all { it == 22.toShort() })
        assertTrue(sipPort.written.single().all { it == 11.toShort() })
    }

    @Test fun incompatibleFormatsEndTheCall() {
        val a = cell.incoming("13800001234"); settle()
        val b = sip.dialed.single()
        b.remoteAnswered(); settle()
        val cellPort = FakePort(true, 11, PcmFormat.WIDEBAND)
        a.mediaUp(cellPort); b.mediaUp(FakePort(false, 22)); settle()
        assertNull(bridge.session)
        assertEquals(HangupCause.INTERNAL_ERROR, a.endCause)
        assertEquals(HangupCause.INTERNAL_ERROR, b.endCause)
        assertNull(cellPort.clock)
    }
}
