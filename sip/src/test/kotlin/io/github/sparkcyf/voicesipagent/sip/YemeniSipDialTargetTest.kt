package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YemeniSipDialTargetTest {
    private fun request(uri: String = "sip:gateway@sip2sip.info", headers: SipHeaders = SipHeaders()) =
        SipRequest("INVITE", uri, headers.apply {
            add("Via", "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKtest")
            add("From", "<sip:caller@example.net>;tag=from")
            if (get("To") == null) add("To", "<sip:gateway@sip2sip.info>")
            add("Call-ID", "dynamic-target-test")
            add("CSeq", "1 INVITE")
        })

    private fun accepted(req: SipRequest, expected: String, source: String) {
        val result = YemeniSipDialTarget.resolve(req)
        assertTrue("expected accepted target, got $result", result is YemeniSipDialTarget.Result.Accepted)
        val target = (result as YemeniSipDialTarget.Result.Accepted).target
        assertEquals(expected, target.e164)
        assertEquals(source, target.source)
    }

    private fun rejected(req: SipRequest) {
        val result = YemeniSipDialTarget.resolve(req)
        assertTrue("expected rejection, got $result", result is YemeniSipDialTarget.Result.Rejected)
    }

    @Test fun xVsaDialIsHighestPriority() {
        accepted(
            request(headers = SipHeaders().add("X-VSA-Dial", "sip777405578")
                .add("To", "<sip:sip777405579@sip2sip.info>")
                .add("P-Called-Party-ID", "<sip:sip777405580@sip2sip.info>")),
            "+967777405578",
            "X-VSA-Dial",
        )
    }

    @Test fun toWinsOverCalledPartyAndFixedRequestUri() {
        accepted(
            request(
                uri = "sip:gateway@sip2sip.info",
                headers = SipHeaders()
                    .add("To", "<sip:sip777405578@sip2sip.info>")
                    .add("P-Called-Party-ID", "<sip:sip777405579@sip2sip.info>"),
            ),
            "+967777405578",
            "To",
        )
    }

    @Test fun xVsaDialAcceptsAFullSipUriAsItsValue() {
        accepted(
            request(headers = SipHeaders().add("X-VSA-Dial", "sip:sip777405578@sip2sip.info")),
            "+967777405578",
            "X-VSA-Dial",
        )
    }

    @Test fun pCalledPartyIdIsUsedWhenToHasNoDynamicUser() {
        accepted(
            request(
                headers = SipHeaders()
                    .add("To", "<sip:gateway@sip2sip.info>")
                    .add("P-Called-Party-ID", "<sip:sip777405578@sip2sip.info>"),
            ),
            "+967777405578",
            "P-Called-Party-ID",
        )
    }

    @Test fun requestUriIsTheLastFallback() {
        accepted(
            request(
                uri = "sip:sip777405578@sip2sip.info",
                headers = SipHeaders().add("To", "<sip:gateway@sip2sip.info>"),
            ),
            "+967777405578",
            "Request-URI",
        )
    }

    @Test fun acceptsEachSupportedYemeniMobilePrefix() {
        listOf("70", "71", "73", "77", "78").forEach { prefix ->
            accepted(
                request(headers = SipHeaders().add("X-VSA-Dial", "sip" + prefix + "1234567")),
                "+967" + prefix + "1234567",
                "X-VSA-Dial",
            )
        }
    }

    @Test fun rejectsMissingBareShortLongEmergencyAndNonNumericTargets() {
        rejected(request(headers = SipHeaders().add("To", "<sip:gateway@sip2sip.info>")))
        listOf(
            "777405578",
            "sip12345",
            "sip7774055780",
            "sip911",
            "sipabc123",
            "sip601234567",
        ).forEach { value ->
            rejected(request(headers = SipHeaders().add("X-VSA-Dial", value)))
        }
    }

    @Test fun invalidHigherPriorityValueDoesNotFallThrough() {
        rejected(
            request(
                uri = "sip:sip777405578@sip2sip.info",
                headers = SipHeaders()
                    .add("X-VSA-Dial", "777405578")
                    .add("To", "<sip:sip777405578@sip2sip.info>"),
            ),
        )
    }
}
