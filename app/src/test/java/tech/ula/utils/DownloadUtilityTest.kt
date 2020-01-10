package tech.ula.utils

import android.app.DownloadManager
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.nhaarman.mockitokotlin2.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.Session
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter

@RunWith(MockitoJUnitRunner::class)
class DownloadUtilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Mock
    lateinit var downloadManager: DownloadManager

    @Mock
    lateinit var preferenceUtility: PreferenceUtility

    @Mock
    lateinit var connectivityManager: ConnectivityManager

    @Mock
    lateinit var network: Network

    @Mock
    lateinit var networkInfo: NetworkInfo

    @Mock
    lateinit var networkCapabilities: NetworkCapabilities

    @Mock
    lateinit var connectionUtility: ConnectionUtility


    lateinit var downloadUtility: DownloadUtility

    lateinit var applicationFilesDirPath: String

    @Before
    fun setup() {
        applicationFilesDirPath = tempFolder.root.path
    }

    @Test
    fun largeAssetIsRequiredAndThereIsNoWifi() {
        val session = Session(0, filesystemId = 0, isExtracted = false)
        val filesystem = Filesystem(0, isDownloaded = false)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        `when`(connectivityManager.allNetworks).thenReturn(arrayOf())

        assertTrue(downloadUtility.largeAssetRequiredAndNoWifi())
    }

    @Test
    fun largeAssetIsNotRequiredAndThereIsNotWifi() {
        val session = Session(0, filesystemId = 0, isExtracted = true)
        val filesystem = Filesystem(0, isDownloaded = true)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        `when`(connectivityManager.allNetworks).thenReturn(arrayOf())

        assertFalse(downloadUtility.largeAssetRequiredAndNoWifi())
    }

    @Test
    fun largeAssetIsRequiredAndThereIsWifi() {
        val session = Session(0, filesystemId = 0, isExtracted = false)
        val filesystem = Filesystem(0, isDownloaded = false)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        `when`(connectivityManager.allNetworks).thenReturn(arrayOf(network))
        `when`(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        `when`(networkCapabilities.hasTransport(anyInt())).thenReturn(true)

        assertFalse(downloadUtility.largeAssetRequiredAndNoWifi())
    }

    @Test
    fun largeAssetIsNotRequiredAndThereIsWifi() {
        val session = Session(0, filesystemId = 0, isExtracted = true)
        val filesystem = Filesystem(0, isDownloaded = true)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        `when`(connectivityManager.allNetworks).thenReturn(arrayOf(network))
        `when`(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
        `when`(networkCapabilities.hasTransport(anyInt())).thenReturn(true)

        assertFalse(downloadUtility.largeAssetRequiredAndNoWifi())
    }

    @Test
    fun internetIsAccessibleWhenActiveNetworkInfoPresent() {
        val session = Session(0, filesystemId = 0)
        val filesystem = Filesystem(0)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        `when`(connectivityManager.activeNetworkInfo).thenReturn(networkInfo)
        assertTrue(downloadUtility.internetIsAccessible())
    }

    @Test
    fun internetIsAccessibleWhenActiveNetworkInfoNotPresent() {
        val session = Session(0, filesystemId = 0)
        val filesystem = Filesystem(0)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        assertFalse(downloadUtility.internetIsAccessible())
    }

    @Test
    fun downloadsRequirementsWhenForced() {
        val session = Session(0, filesystemId = 0)
        val filesystem = Filesystem(0)
        downloadUtility = DownloadUtility(session, filesystem, downloadManager, preferenceUtility, applicationFilesDirPath, connectivityManager)

        val assetList = File("${tempFolder.root.path}/assets.txt")
        val writer = FileWriter(assetList)
        writer.write("asset1 1000")
        writer.flush()
        writer.close()

        val inputStream = FileInputStream(assetList)
        `when`(connectionUtility.getAssetListConnection(anyString())).thenReturn(inputStream)

        `when`(preferenceUtility.getSavedTimestampForFile(anyString())).thenReturn(999)

        downloadUtility.downloadRequirements(true, listOf("repo" to "scope"))
        verify(downloadManager).enqueue()
    }
}