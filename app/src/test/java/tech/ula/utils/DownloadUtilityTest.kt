package tech.ula.utils

import android.app.DownloadManager
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.* // ktlint-disable no-wildcard-imports
import org.mockito.junit.MockitoJUnitRunner
import tech.ula.model.entities.Asset
import java.io.File
import kotlin.text.Charsets.UTF_8

@RunWith(MockitoJUnitRunner::class)
class DownloadUtilityTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Mock lateinit var assetPreferences: AssetPreferences

    @Mock lateinit var downloadManagerWrapper: DownloadManagerWrapper

    @Mock lateinit var requestReturn1: DownloadManager.Request

    @Mock lateinit var requestReturn2: DownloadManager.Request

    lateinit var downloadDirectory: File

    val asset1 = Asset("name1", "distType1", "archType1", 0)
    val asset2 = Asset("name2", "distType2", "archType2", 0)
    val assetList = listOf(asset1, asset2)

    val url1 = getDownloadUrl(asset1.distributionType, asset1.architectureType, asset1.name)
    val destination1 = asset1.concatenatedName

    val url2 = getDownloadUrl(asset2.distributionType, asset2.architectureType, asset2.name)
    val destination2 = asset2.concatenatedName

    lateinit var downloadUtility: DownloadUtility

    @Before
    fun setup() {
        downloadDirectory = tempFolder.newFolder("downloads")
        whenever(downloadManagerWrapper.getDownloadsDirectory())
                .thenReturn(downloadDirectory)
        whenever(downloadManagerWrapper.generateDownloadRequest(url1, destination1))
                .thenReturn(requestReturn1)
        whenever(downloadManagerWrapper.generateDownloadRequest(url2, destination2))
                .thenReturn(requestReturn2)

        downloadUtility = DownloadUtility(assetPreferences, downloadManagerWrapper, applicationFilesDir = tempFolder.root)
    }

    private fun getDownloadUrl(distType: String, archType: String, name: String): String {
        val branch = "master"
        return "https://github.com/CypherpunkArmory/UserLAnd-Assets-$distType/raw/$branch/assets/$archType/$name"
    }

    @Test
    fun `Returns appropriate value from asset preferences about whether cache is populated`() {
        val expectedFirstResult = true
        val expectedSecondResult = false
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(expectedFirstResult)
                .thenReturn(expectedSecondResult)

        val firstResult = downloadUtility.downloadStateHasBeenCached()
        val secondResult = downloadUtility.downloadStateHasBeenCached()

        assertEquals(expectedFirstResult, firstResult)
        assertEquals(expectedSecondResult, secondResult)
    }

    @Test
    fun `Returns CacheSyncAttemptedWhileCacheIsEmpty if sync cache called while nothing is cached`() {
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(false)

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is CacheSyncAttemptedWhileCacheIsEmpty)
    }

    @Test
    fun `Returns AssetDownloadFailure while syncing if any cached downloads failed`() {
        val downloadId = 0L
        val failureReason = "fail"
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(setOf(downloadId))

        whenever(downloadManagerWrapper.downloadHasFailed(downloadId))
                .thenReturn(true)
        whenever(downloadManagerWrapper.getDownloadFailureReason(downloadId))
                .thenReturn(failureReason)

        val result = downloadUtility.syncStateWithCache()
        assertTrue(result is AssetDownloadFailure)
        val cast = result as AssetDownloadFailure
        assertEquals(failureReason, cast.reason)
    }

    @Test
    fun `Returns AllDownloadsCompletedSuccessfully if all downloads have completed since cache was updated`() {
        val downloadId = 0L
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(setOf(downloadId))
        whenever(downloadManagerWrapper.downloadHasFailed(downloadId))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(downloadId))
                .thenReturn(true)
        whenever(downloadManagerWrapper.getDownloadTitle(downloadId))
                .thenReturn("title")

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is AllDownloadsCompletedSuccessfully)
        verify(assetPreferences).setDownloadsAreInProgress(false)
        verify(assetPreferences).clearEnqueuedDownloadsCache()
    }

    @Test
    fun `Returns CompletedDownloadsUpdate if downloads are still in progress during sync`() {
        val downloadIds = setOf<Long>(0, 1)
        whenever(assetPreferences.getDownloadsAreInProgress())
                .thenReturn(true)
        whenever(assetPreferences.getEnqueuedDownloads())
                .thenReturn(downloadIds)
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(0))
                .thenReturn(true)
        whenever(downloadManagerWrapper.downloadHasFailed(1))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasSucceeded(1))
                .thenReturn(false)
        whenever(downloadManagerWrapper.getDownloadTitle(0))
                .thenReturn("title")

        val result = downloadUtility.syncStateWithCache()

        assertTrue(result is CompletedDownloadsUpdate)
        val cast = result as CompletedDownloadsUpdate
        assertEquals(1, cast.numCompleted)
        assertEquals(2, cast.numTotal)
    }

    @Test
    fun `Sets up download process`() {
        whenever(downloadManagerWrapper.enqueue(requestReturn1))
                .thenReturn(0)
        whenever(downloadManagerWrapper.enqueue(requestReturn2))
                .thenReturn(1)

        downloadUtility.downloadRequirements(assetList)

        verify(assetPreferences).clearEnqueuedDownloadsCache()
        verify(assetPreferences).setDownloadsAreInProgress(true)
        verify(assetPreferences).setEnqueuedDownloads(setOf(0, 1))
    }

    private fun setupDownloadState() {
        whenever(downloadManagerWrapper.enqueue(requestReturn1))
                .thenReturn(0)
        whenever(downloadManagerWrapper.enqueue(requestReturn2))
                .thenReturn(1)

        downloadUtility.downloadRequirements(assetList)
    }

    @Test
    fun `Returns NonUserLandDownloadFound if a a download we did not start is found`() {
        setupDownloadState()

        val result = downloadUtility.handleDownloadComplete(-1)

        assertTrue(result is NonUserlandDownloadFound)
    }

    @Test
    fun `Returns AssetDownloadFailure if any downloads fail`() {
        setupDownloadState()
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(true)
        whenever(downloadManagerWrapper.getDownloadFailureReason(0))
                .thenReturn("fail")

        val result = downloadUtility.handleDownloadComplete(0)

        assertTrue(result is AssetDownloadFailure)
        result as AssetDownloadFailure
        assertEquals("fail", result.reason)
    }

    @Test
    fun `Completes downloads and then resets cache when all complete`() {
        setupDownloadState()
        whenever(downloadManagerWrapper.downloadHasFailed(0))
                .thenReturn(false)
        whenever(downloadManagerWrapper.downloadHasFailed(1))
                .thenReturn(false)
        whenever(downloadManagerWrapper.getDownloadTitle(0))
                .thenReturn("userland-")
        whenever(downloadManagerWrapper.getDownloadTitle(1))
                .thenReturn("userland-")

        val result1 = downloadUtility.handleDownloadComplete(0)
        val result2 = downloadUtility.handleDownloadComplete(1)

        assertTrue(result1 is CompletedDownloadsUpdate)
        assertTrue(result2 is AllDownloadsCompletedSuccessfully)
        result1 as CompletedDownloadsUpdate
        result2 as AllDownloadsCompletedSuccessfully
        assertEquals(1, result1.numCompleted)
        assertEquals(2, result1.numTotal)
        verify(assetPreferences).setDownloadsAreInProgress(false)
        verify(assetPreferences, times(2)).clearEnqueuedDownloadsCache()
    }

    @Test
    fun `Clears download directory of userland files`() {
        val asset1DownloadsFile = File("${downloadDirectory.path}/${asset1.concatenatedName}")
        val asset2DownloadsFile = File("${downloadDirectory.path}/${asset2.concatenatedName}")
        asset1DownloadsFile.createNewFile()
        asset2DownloadsFile.createNewFile()
        assertTrue(asset1DownloadsFile.exists())
        assertTrue(asset2DownloadsFile.exists())

        downloadUtility.downloadRequirements(assetList)

        assertFalse(asset1DownloadsFile.exists())
        assertFalse(asset2DownloadsFile.exists())
    }

    @Test
    fun deletesPreviousDownloads() {
        tempFolder.newFolder("distType1")
        tempFolder.newFolder("distType2")
        val asset1File = File("${tempFolder.root.path}/distType1/name1")
        val asset2File = File("${tempFolder.root.path}/distType2/name2")
        asset1File.createNewFile()
        asset2File.createNewFile()
        assertTrue(asset1File.exists())
        assertTrue(asset2File.exists())

        val asset1DownloadsFile = File("${downloadDirectory.path}/${asset1.concatenatedName}")
        val asset2DownloadsFile = File("${downloadDirectory.path}/${asset2.concatenatedName}")
        asset1DownloadsFile.createNewFile()
        asset2DownloadsFile.createNewFile()
        assertTrue(asset1DownloadsFile.exists())
        assertTrue(asset2DownloadsFile.exists())

        downloadUtility.downloadRequirements(assetList)

        assertFalse(asset1File.exists())
        assertFalse(asset2File.exists())
        assertFalse(asset1DownloadsFile.exists())
        assertFalse(asset2DownloadsFile.exists())
    }

    @Test
    fun movesAssetsToCorrectLocationAndUpdatesPermissions() {
        val asset1DownloadsFile = File("${downloadDirectory.path}/${asset1.concatenatedName}")
        val asset2DownloadsFile = File("${downloadDirectory.path}/${asset2.concatenatedName}")
        asset1DownloadsFile.createNewFile()
        asset2DownloadsFile.createNewFile()

        val asset1File = File("${tempFolder.root.path}/distType1/name1")
        val asset2File = File("${tempFolder.root.path}/distType2/name2")
        assertFalse(asset1File.exists())
        assertFalse(asset2File.exists())

        downloadUtility.moveAssetsToCorrectLocalDirectory()

        assertFalse(asset1DownloadsFile.exists())
        assertFalse(asset2DownloadsFile.exists())
        assertTrue(asset1File.exists())
        assertTrue(asset2File.exists())

        var output = ""
        val proc1 = Runtime.getRuntime().exec("ls -l ${asset1File.path}")

        proc1.inputStream.bufferedReader(UTF_8).forEachLine { output += it }
        val permissions1 = output.substring(0, 10)
        assertTrue(permissions1 == "-rwxrwxrwx")

        output = ""
        val proc2 = Runtime.getRuntime().exec("ls -l ${asset2File.path}")

        proc2.inputStream.bufferedReader(UTF_8).forEachLine { output += it }
        val permissions2 = output.substring(0, 10)
        assertTrue(permissions2 == "-rwxrwxrwx")
    }

    @Test
    fun `Can parse a distribution type out of downloaded files`() {
        val assetDownloadFile = File("${downloadDirectory.path}/${asset1.concatenatedName}")
        assetDownloadFile.createNewFile()

        val result = downloadUtility.findDownloadedDistributionType()

        val expected = "distType1"
        assertEquals(expected, result)
    }

    @Test
    fun `Returns empty string if no distributions are found in downloads directory`() {
        assertEquals("", downloadUtility.findDownloadedDistributionType())
    }
}