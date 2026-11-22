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
            LiveServer.ID.findAll(file.readText())
                .map { it.value.replace("-", "") }
                .filterNot { it.startsWith(LiveServer.FAKE_PREFIX) }
                .map { "${file.parentFile.name}/${file.name}: $it" }
                .toList()
        }

        assertTrue(leaks.isEmpty(), "not replaced:\n${leaks.joinToString("\n")}")
    }

    @Test
    fun `no recording holds an address of the machine`() {
        val addresses = Regex("localhost|\\b(?:172|192\\.168)\\.\\d+\\.\\d+")
        val leaks = files.filter { addresses.containsMatchIn(it.readText()) }.map { it.name }

        assertTrue(leaks.isEmpty(), "an address in: $leaks")
    }
}
