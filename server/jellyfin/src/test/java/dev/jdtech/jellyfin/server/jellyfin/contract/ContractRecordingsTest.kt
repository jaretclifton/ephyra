package dev.jdtech.jellyfin.server.jellyfin.contract

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * #771: the recordings under `src/test/resources/contract/` hold nothing of the test server's: no
 * id, access token or image tag (a token is 32 hex digits, like an id, so every such run must be a
 * fake one, starting with [LiveServer.FAKE_PREFIX]), and no address of the machine.
 */
class ContractRecordingsTest {
    private val files =
        File(System.getProperty("contract.resources").orEmpty())
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()

    @Test
    fun `there are recordings`() {
        assertTrue(files.size >= 30, "only ${files.size} recordings found")
    }

    @Test
    fun `no recording holds an id or a token of the server`() {
        val leaks = files.flatMap { file ->
            (LiveServer.ID.findAll(file.readText()) + LiveServer.SECRET.findAll(file.readText()))
                .map { it.value.replace("-", "") }
                .filterNot { it.startsWith(LiveServer.FAKE_PREFIX) }
                .map { "${file.parentFile.name}/${file.name}: $it" }
                .toList()
        }

        assertTrue(leaks.isEmpty(), "not replaced:\n${leaks.joinToString("\n")}")
    }

    @Test
    fun `no recording holds an address or a host name of the machine`() {
        val leaks = files.flatMap { file ->
            val text = file.readText()
            val ipv4 = IPV4.findAll(text).map { it.value }.filter { it != "127.0.0.1" }
            // A run of colons and hex digits with a letter or `::` in it; a time has neither.
            val ipv6 =
                IPV6.findAll(text)
                    .map { it.value }
                    .filter { "::" in it || it.any { c -> c in 'a'..'f' || c in 'A'..'F' } }
            val hosts = HOST.findAll(text).map { it.value }
            (ipv4 + ipv6 + hosts).map { "${file.parentFile.name}/${file.name}: $it" }.toList()
        }

        assertTrue(leaks.isEmpty(), "an address in:\n${leaks.joinToString("\n")}")
    }

    private companion object {
        val IPV4 = Regex("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\d.])")
        val IPV6 =
            Regex("(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])")
        val HOST =
            Regex(
                "localhost|host\\.docker\\.internal|[A-Za-z0-9-]+\\.(?:local|lan|internal|home\\.arpa)\\b",
                RegexOption.IGNORE_CASE,
            )
    }
}
