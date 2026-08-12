package com.huanfuli.lapsight

import com.huanfuli.lapsight.shared.NtripSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

class AndroidNtripClientTest {
    @Test
    fun buildsAuthenticatedNtripRequestWithoutLoggingCredentials() {
        val request = buildNtripRequest(
            NtripSettings(
                enabled = true,
                host = "caster.example",
                port = 2101,
                mountpoint = "/MSM4_VRS",
                username = "driver",
                password = "secret",
            ),
        ).decodeToString()

        assertTrue(request.startsWith("GET /MSM4_VRS HTTP/1.0\r\n"))
        assertTrue(request.contains("Host: caster.example:2101\r\n"))
        assertTrue(request.contains("Authorization: Basic ZHJpdmVyOnNlY3JldA==\r\n"))
        assertFalse(request.contains("driver:secret"))
    }

    @Test
    fun acceptsIcyAndHttpSuccessResponses() {
        readSuccessfulNtripResponse(stream("ICY 200 OK\r\n"))
        readSuccessfulNtripResponse(stream("HTTP/1.1 200 OK\r\nContent-Type: gnss/data\r\n\r\n"))
    }

    @Test(expected = IOException::class)
    fun rejectsUnauthorizedCasterResponse() {
        readSuccessfulNtripResponse(stream("HTTP/1.1 401 Unauthorized\r\n\r\n"))
    }

    private fun stream(value: String): BufferedInputStream =
        BufferedInputStream(ByteArrayInputStream(value.encodeToByteArray()))
}
