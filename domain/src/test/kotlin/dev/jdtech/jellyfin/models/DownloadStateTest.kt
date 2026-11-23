package dev.jdtech.jellyfin.models

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DownloadStateTest {
    private fun source(type: ItemSourceType, path: String) =
        ItemSource(
            id = "source",
            name = "source",
            type = type,
            path = path,
            size = 0L,
            mediaStreams = emptyList(),
        )

    @Test
    fun `a remote source is not a download`() {
        assertEquals(
            DownloadState.NONE,
            source(ItemSourceType.REMOTE, "http://server/video").downloadState,
        )
        // Only a local source can be a download, whatever its path ends in.
        assertEquals(
            DownloadState.NONE,
            source(ItemSourceType.REMOTE, "/x/film.mkv.download").downloadState,
        )
    }

    @Test
    fun `a local source with the partial name is downloading`() {
        assertEquals(
            DownloadState.DOWNLOADING,
            source(ItemSourceType.LOCAL, "/downloads/film.mkv.download").downloadState,
        )
    }

    @Test
    fun `a local source under its final name is finished`() {
        assertEquals(
            DownloadState.FINISHED,
            source(ItemSourceType.LOCAL, "/downloads/film.mkv").downloadState,
        )
        // The suffix counts only at the end.
        assertEquals(
            DownloadState.FINISHED,
            source(ItemSourceType.LOCAL, "/downloads/film.download.mkv").downloadState,
        )
    }

    @Test
    fun `the file names go back and forth`() {
        assertEquals(".download", DownloadFiles.PARTIAL_SUFFIX)
        assertEquals("/d/a.mkv.download", DownloadFiles.partialPath("/d/a.mkv"))
        assertEquals("/d/a.mkv", DownloadFiles.finalPath("/d/a.mkv.download"))
        // A final name stays as it is, and only one suffix goes.
        assertEquals("/d/a.mkv", DownloadFiles.finalPath("/d/a.mkv"))
        assertEquals("/d/a.download", DownloadFiles.finalPath("/d/a.download.download"))
        assertEquals("%.download", DownloadFiles.PARTIAL_LIKE)
        assertTrue(DownloadFiles.isPartial("/d/a.mkv.download"))
        assertFalse(DownloadFiles.isPartial("/d/a.mkv"))
    }
}
