package tech.ula.model.state

import android.arch.core.executor.testing.InstantTaskExecutorRule
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import com.nhaarman.mockitokotlin2.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.ula.model.daos.FilesystemDao
import tech.ula.model.daos.SessionDao
import tech.ula.model.entities.Asset
import tech.ula.model.entities.Filesystem
import tech.ula.model.entities.Session
import tech.ula.model.repositories.AssetRepository
import tech.ula.model.repositories.UlaDatabase
import tech.ula.utils.CrashlyticsWrapper
import tech.ula.utils.DownloadUtility
import tech.ula.utils.FilesystemUtility
import tech.ula.utils.TimeUtility
import kotlin.Exception

@RunWith(MockitoJUnitRunner::class)
class SessionStartupFsmTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    // Mocks

    @Mock lateinit var mockUlaDatabase: UlaDatabase

    @Mock lateinit var mockSessionDao: SessionDao

    @Mock lateinit var mockFilesystemDao: FilesystemDao

    @Mock lateinit var mockAssetRepository: AssetRepository

    @Mock lateinit var mockDownloadUtility: DownloadUtility

    @Mock lateinit var mockFilesystemUtility: FilesystemUtility

    @Mock lateinit var mockTimeUtility: TimeUtility

    @Mock lateinit var mockStateObserver: Observer<SessionStartupState>

    @Mock lateinit var mockCrashlyticsWrapper: CrashlyticsWrapper

    lateinit var activeSessionLiveData: MutableLiveData<List<Session>>

    lateinit var sessionFsm: SessionStartupFsm

    // Test setup variables
    val activeSession = Session(id = -1, name = "active", filesystemId = -1, active = true)
    val inactiveSession = Session(id = -1, name = "inactive", filesystemId = -1, active = false)

    val asset = Asset("asset", "arch", "dist", -1)
    val largeAsset = Asset("rootfs.tar.gz", "arch", "dist", -1)
    val singleAssetList = listOf(asset)
    val assetLists = listOf(singleAssetList)
    val assetListsWithLargeAsset = listOf(listOf(asset, largeAsset))
    val emptyAssetLists = listOf(listOf<Asset>())

    val filesystem = Filesystem(id = -1)

    val incorrectTransitionEvent = SessionSelected(inactiveSession)
    val incorrectTransitionState = RetrievingAssetLists
    val possibleEvents = listOf(
            SessionSelected(inactiveSession),
            RetrieveAssetLists(filesystem),
            GenerateDownloads(filesystem, assetLists),
            DownloadAssets(singleAssetList),
            AssetDownloadComplete(0),
            CopyDownloadsToLocalStorage(filesystem),
            ExtractFilesystem(filesystem),
            VerifyFilesystemAssets(filesystem)
    )
    val possibleStates = listOf(
            IncorrectSessionTransition(incorrectTransitionEvent, incorrectTransitionState),
            WaitingForSessionSelection,
            SingleSessionSupported,
            SessionIsRestartable(inactiveSession),
            SessionIsReadyForPreparation(inactiveSession, filesystem),
            RetrievingAssetLists,
            AssetListsRetrievalSucceeded(assetLists),
            AssetListsRetrievalFailed,
            GeneratingDownloadRequirements,
            NoDownloadsRequired,
            DownloadsRequired(singleAssetList, false),
            DownloadingRequirements(0, 0),
            DownloadsHaveSucceeded,
            DownloadsHaveFailed(""),
            CopyingFilesToRequiredDirectories,
            CopyingSucceeded,
            CopyingFailed,
            ExtractingFilesystem("test"),
            ExtractionSucceeded,
            ExtractionFailed,
            VerifyingFilesystemAssets,
            FilesystemHasRequiredAssets,
            FilesystemIsMissingRequiredAssets
    )

    @Before
    fun setup() {
        activeSessionLiveData = MutableLiveData()
        val filesystemLiveData = MutableLiveData<List<Filesystem>>().apply { postValue(listOf(filesystem)) }

        whenever(mockUlaDatabase.sessionDao()).thenReturn(mockSessionDao)
        whenever(mockSessionDao.findActiveSessions()).thenReturn(activeSessionLiveData)
        whenever(mockUlaDatabase.filesystemDao()).thenReturn(mockFilesystemDao)
        whenever(mockFilesystemDao.getAllFilesystems()).thenReturn(filesystemLiveData)

        sessionFsm = SessionStartupFsm(mockUlaDatabase, mockAssetRepository, mockFilesystemUtility, mockDownloadUtility, mockTimeUtility, mockCrashlyticsWrapper)
    }

    @After
    fun teardown() {
        activeSessionLiveData = MutableLiveData()
    }

    @Test
    fun `Only allows correct state transitions`() {
        sessionFsm.getState().observeForever(mockStateObserver)

        for (event in possibleEvents) {
            for (state in possibleStates) {
                sessionFsm.setState(state)
                val result = sessionFsm.transitionIsAcceptable(event)
                when {
                    event is SessionSelected && state is WaitingForSessionSelection -> assertTrue(result)
                    event is RetrieveAssetLists && state is SessionIsReadyForPreparation -> assertTrue(result)
                    event is GenerateDownloads && state is AssetListsRetrievalSucceeded -> assertTrue(result)
                    event is DownloadAssets && state is DownloadsRequired -> assertTrue(result)
                    event is AssetDownloadComplete && state is DownloadingRequirements -> assertTrue(result)
                    event is CopyDownloadsToLocalStorage && state is DownloadsHaveSucceeded -> assertTrue(result)
                    event is ExtractFilesystem && (state is NoDownloadsRequired || state is CopyingSucceeded) -> assertTrue(result)
                    event is VerifyFilesystemAssets && state is ExtractionSucceeded -> assertTrue(result)
                    event is ResetSessionState -> assertTrue(result)
                    else -> assertFalse(result)
                }
            }
        }
    }

    @Test
    fun `Exits early if incorrect transition event submitted`() {
        val state = WaitingForSessionSelection
        sessionFsm.setState(state)
        sessionFsm.getState().observeForever(mockStateObserver)

        val event = RetrieveAssetLists(filesystem)
        runBlocking { sessionFsm.submitEvent(event, this) }

        verify(mockStateObserver, times(1)).onChanged(IncorrectSessionTransition(event, state))
        verify(mockStateObserver, times(2)).onChanged(any()) // Observes when registered and again on state emission
    }

    @Test
    fun `Initial state is WaitingForSessionSelection`() {
        sessionFsm.getState().observeForever(mockStateObserver)

        verify(mockStateObserver).onChanged(WaitingForSessionSelection)
    }

    @Test
    fun `State can be reset`() {
        sessionFsm.getState().observeForever(mockStateObserver)

        for (state in possibleStates) {
            sessionFsm.setState(state)
            runBlocking { sessionFsm.submitEvent(ResetSessionState, this) }
        }

        val numberOfStates = possibleStates.size
        // Will initially be WaitingForSessionSelection (+1), the test for that state (+1), and then reset for each
        verify(mockStateObserver, times(numberOfStates + 2)).onChanged(WaitingForSessionSelection)
    }

    @Test
    fun `State is SingleSessionSupported if active session is not selected one`() {
        sessionFsm.setState(WaitingForSessionSelection)
        sessionFsm.getState().observeForever(mockStateObserver)
        activeSessionLiveData.postValue(listOf(activeSession))

        val differentSession = Session(id = 0, name = "inactive", filesystemId = -1, active = false)

        runBlocking { sessionFsm.submitEvent(SessionSelected(differentSession), this) }

        verify(mockStateObserver).onChanged(SingleSessionSupported)
    }

    @Test
    fun `State is SessionIsRestartable if active session is selected one`() {
        sessionFsm.setState(WaitingForSessionSelection)
        sessionFsm.getState().observeForever(mockStateObserver)
        activeSessionLiveData.postValue(listOf(activeSession))

        runBlocking { sessionFsm.submitEvent(SessionSelected(activeSession), this) }

        verify(mockStateObserver).onChanged(SessionIsRestartable(activeSession))
    }

    @Test
    fun `State is SessionIsReadyForPreparation if there are no active sessions on selection`() {
        sessionFsm.setState(WaitingForSessionSelection)
        sessionFsm.getState().observeForever(mockStateObserver)
        activeSessionLiveData.postValue(listOf())

        runBlocking { sessionFsm.submitEvent(SessionSelected(inactiveSession), this) }

        verify(mockStateObserver).onChanged(SessionIsReadyForPreparation(inactiveSession, filesystem))
    }

    @Test
    fun `State is RetrievingAssetLists and then AssetListsRetrieved`() {
        sessionFsm.setState(SessionIsReadyForPreparation(inactiveSession, filesystem))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.getAllAssetLists(filesystem.distributionType, filesystem.archType))
                .thenReturn(assetLists)

        runBlocking { sessionFsm.submitEvent(RetrieveAssetLists(filesystem), this) }

        verify(mockStateObserver).onChanged(RetrievingAssetLists)
        verify(mockStateObserver).onChanged(AssetListsRetrievalSucceeded(assetLists))
    }

    @Test
    fun `State is AssetListsRetrievalFailed if remote and cached assets cannot be fetched`() {
        sessionFsm.setState(SessionIsReadyForPreparation(inactiveSession, filesystem))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.getAllAssetLists(filesystem.distributionType, filesystem.archType))
                .thenReturn(emptyAssetLists)

        runBlocking { sessionFsm.submitEvent(RetrieveAssetLists(filesystem), this) }

        verify(mockStateObserver).onChanged(RetrievingAssetLists)
        verify(mockStateObserver).onChanged(AssetListsRetrievalFailed)
    }

    @Test
    fun `State is DownloadsRequired and largeDownloadRequired is true if a rootfs needs to be downloaded`() {
        sessionFsm.setState(AssetListsRetrievalSucceeded(assetListsWithLargeAsset))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.doesAssetNeedToUpdated(asset))
                .thenReturn(true)
        whenever(mockAssetRepository.doesAssetNeedToUpdated(largeAsset))
                .thenReturn(true)
        whenever(mockFilesystemUtility.hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}"))
                .thenReturn(false)

        runBlocking { sessionFsm.submitEvent(GenerateDownloads(filesystem, assetListsWithLargeAsset), this) }

        verify(mockStateObserver).onChanged(GeneratingDownloadRequirements)
        verify(mockStateObserver).onChanged(DownloadsRequired(assetListsWithLargeAsset.flatten(), largeDownloadRequired = true))
    }

    @Test
    fun `State is DownloadsRequired and largeDownloadRequired is false if a rootfs needs updating but the filesystem already exists`() {
        sessionFsm.setState(AssetListsRetrievalSucceeded(assetListsWithLargeAsset))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.doesAssetNeedToUpdated(asset))
                .thenReturn(true)
        whenever(mockAssetRepository.doesAssetNeedToUpdated(largeAsset))
                .thenReturn(true)
        whenever(mockFilesystemUtility.hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}"))
                .thenReturn(true)

        runBlocking { sessionFsm.submitEvent(GenerateDownloads(filesystem, assetListsWithLargeAsset), this) }

        verify(mockStateObserver).onChanged(GeneratingDownloadRequirements)
        verify(mockStateObserver).onChanged(DownloadsRequired(singleAssetList, largeDownloadRequired = false))
    }

    @Test
    fun `State is DownloadsRequired and includes false if downloads do not include rootfs`() {
        sessionFsm.setState(AssetListsRetrievalSucceeded(assetLists))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.doesAssetNeedToUpdated(asset))
                .thenReturn(true)

        runBlocking { sessionFsm.submitEvent(GenerateDownloads(filesystem, assetLists), this) }

        verify(mockStateObserver).onChanged(GeneratingDownloadRequirements)
        verify(mockStateObserver).onChanged(DownloadsRequired(assetLists.flatten(), largeDownloadRequired = false))
    }

    @Test
    fun `State is NoDownloadsRequired if everything is up to date`() {
        sessionFsm.setState(AssetListsRetrievalSucceeded(assetLists))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.doesAssetNeedToUpdated(asset))
                .thenReturn(false)

        runBlocking { sessionFsm.submitEvent(GenerateDownloads(filesystem, assetLists), this) }

        verify(mockStateObserver).onChanged(GeneratingDownloadRequirements)
        verify(mockStateObserver).onChanged(NoDownloadsRequired)
    }

    @Test
    fun `State is DownloadsHaveSucceeded once downloads succeed`() {
        val downloadList = listOf(asset, largeAsset)
        sessionFsm.setState(DownloadsRequired(downloadList, true))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockDownloadUtility.downloadRequirements(downloadList))
                .thenReturn(listOf(0L, 1L))
        whenever(mockDownloadUtility.downloadedSuccessfully(0))
                .thenReturn(true)
        whenever(mockDownloadUtility.downloadedSuccessfully(1))
                .thenReturn(true)

        runBlocking {
            sessionFsm.submitEvent(DownloadAssets(downloadList), this)
            sessionFsm.submitEvent(AssetDownloadComplete(0), this)
            sessionFsm.submitEvent(AssetDownloadComplete(1), this)
        }

        verify(mockDownloadUtility).setTimestampForDownloadedFile(0)
        verify(mockDownloadUtility).setTimestampForDownloadedFile(1)
        verify(mockStateObserver).onChanged(DownloadingRequirements(0, 2))
        verify(mockStateObserver).onChanged(DownloadingRequirements(1, 2))
        verify(mockStateObserver).onChanged(DownloadsHaveSucceeded)
    }

    @Test
    fun `State is DownloadsHaveFailed if any downloads fail`() {
        val downloadList = listOf(asset, largeAsset)
        sessionFsm.setState(DownloadsRequired(downloadList, true))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockDownloadUtility.downloadRequirements(downloadList))
                .thenReturn(listOf(0L, 1L))
        whenever(mockDownloadUtility.downloadedSuccessfully(0))
                .thenReturn(true)
        whenever(mockDownloadUtility.downloadedSuccessfully(1))
                .thenReturn(false)
        whenever(mockDownloadUtility.getReasonForDownloadFailure(1))
                .thenReturn("fail")

        runBlocking {
            sessionFsm.submitEvent(DownloadAssets(downloadList), this)
            sessionFsm.submitEvent(AssetDownloadComplete(0), this)
            sessionFsm.submitEvent(AssetDownloadComplete(1), this)
        }

        verify(mockDownloadUtility).setTimestampForDownloadedFile(0)
        verify(mockDownloadUtility, never()).setTimestampForDownloadedFile(1)
        verify(mockStateObserver).onChanged(DownloadingRequirements(0, 2))
        verify(mockStateObserver).onChanged(DownloadingRequirements(1, 2))
        verify(mockStateObserver).onChanged(DownloadsHaveFailed("fail"))
    }

    @Test
    fun `State is DownloadsHaveFailed with reason that we registered an non-enqueued download`() {
        val downloadList = listOf(asset, largeAsset)
        sessionFsm.setState(DownloadsRequired(downloadList, true))
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockDownloadUtility.downloadRequirements(downloadList))
                .thenReturn(listOf(0L, 1L))
        whenever(mockDownloadUtility.downloadedSuccessfully(0))
                .thenReturn(true)
        whenever(mockDownloadUtility.downloadedSuccessfully(2))
                .thenReturn(true)

        runBlocking {
            sessionFsm.submitEvent(DownloadAssets(downloadList), this)
            sessionFsm.submitEvent(AssetDownloadComplete(0), this)
            sessionFsm.submitEvent(AssetDownloadComplete(2), this)
        }

        verify(mockDownloadUtility).setTimestampForDownloadedFile(0)
        verify(mockDownloadUtility, never()).setTimestampForDownloadedFile(1)
        verify(mockStateObserver).onChanged(DownloadingRequirements(0, 2))
        verify(mockStateObserver).onChanged(DownloadingRequirements(1, 2))
        verify(mockStateObserver).onChanged(DownloadsHaveFailed("Downloads completed with non-enqueued downloads"))
    }

    @Test
    fun `State is CopyingSucceeded if files are moved to correct subdirectories`() {
        sessionFsm.setState(DownloadsHaveSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        val filesystemUpdateTime = 5L
        whenever(mockTimeUtility.getCurrentTimeMillis())
                .thenReturn(filesystemUpdateTime)

        runBlocking { sessionFsm.submitEvent(CopyDownloadsToLocalStorage(filesystem), this) }

        val updatedFilesystem = filesystem
        updatedFilesystem.lastUpdated = filesystemUpdateTime
        verify(mockDownloadUtility).moveAssetsToCorrectLocalDirectory()
        verify(mockFilesystemUtility).copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType)
        verify(mockFilesystemDao).updateFilesystem(updatedFilesystem)
        verify(mockStateObserver).onChanged(CopyingFilesToRequiredDirectories)
        verify(mockStateObserver).onChanged(CopyingSucceeded)
    }

    @Test
    fun `State is DistributionCopyFailed if distribution assets are not copied to filesystem`() {
        sessionFsm.setState(DownloadsHaveSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemUtility.copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType))
                .thenThrow(Exception())

        runBlocking { sessionFsm.submitEvent(CopyDownloadsToLocalStorage(filesystem), this) }

        verify(mockDownloadUtility).moveAssetsToCorrectLocalDirectory()
        verify(mockFilesystemUtility).copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType)
        verify(mockFilesystemDao, never()).updateFilesystem(any())
        verify(mockStateObserver).onChanged(CopyingFilesToRequiredDirectories)
        verify(mockStateObserver).onChanged(DistributionCopyFailed)
    }

    @Test
    fun `State is CopyingFailed if a problem arises`() {
        sessionFsm.setState(DownloadsHaveSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockDownloadUtility.moveAssetsToCorrectLocalDirectory())
                .thenThrow(Exception())

        // TODO update test
        runBlocking { sessionFsm.submitEvent(CopyDownloadsToLocalStorage(filesystem), this) }

        verify(mockStateObserver).onChanged(CopyingFilesToRequiredDirectories)
        verify(mockStateObserver).onChanged(CopyingFailed)
    }

    @Test
    fun `Exits early if filesystem is already extracted`() {
        sessionFsm.setState(CopyingSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemUtility.hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}"))
                .thenReturn(true)

        runBlocking { sessionFsm.submitEvent(ExtractFilesystem(filesystem), this) }

        verify(mockFilesystemUtility, times(1)).hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}")
        verify(mockStateObserver).onChanged(ExtractionSucceeded)
    }

    @Test
    fun `State is ExtractionSucceeded if extraction succeeds`() {
        sessionFsm.setState(CopyingSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemUtility.hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}"))
                .thenReturn(false)
                .thenReturn(true)

        runBlocking { sessionFsm.submitEvent(ExtractFilesystem(filesystem), this) }

        // TODO is there some way to verify extraction steps?
        verify(mockStateObserver).onChanged(ExtractionSucceeded)
    }

    @Test
    fun `State is ExtractionFailed if extraction fails`() {
        sessionFsm.setState(CopyingSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemUtility.hasFilesystemBeenSuccessfullyExtracted("${filesystem.id}"))
                .thenReturn(false)
                .thenReturn(false)

        runBlocking { sessionFsm.submitEvent(ExtractFilesystem(filesystem), this) }

        verify(mockStateObserver).onChanged(ExtractionFailed)
    }

    @Test
    fun `State is FilesystemHasRequiredAssets if all assets are present`() {
        sessionFsm.setState(ExtractionSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.getDistributionAssetsForExistingFilesystem(filesystem))
                .thenReturn(singleAssetList)
        whenever(mockFilesystemUtility.areAllRequiredAssetsPresent("${filesystem.id}", singleAssetList))
                .thenReturn(true)
        whenever(mockAssetRepository.getLastDistributionUpdate(filesystem.distributionType))
                .thenReturn(filesystem.lastUpdated)

        runBlocking { sessionFsm.submitEvent(VerifyFilesystemAssets(filesystem), this) }

        verify(mockStateObserver).onChanged(VerifyingFilesystemAssets)
        verify(mockStateObserver).onChanged(FilesystemHasRequiredAssets)
    }

    @Test
    fun `State is FilesystemHasRequiredAssets if it needs to copy filesystem assets and succeeds`() {
        sessionFsm.setState(ExtractionSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        val filesystemUpdateTime = 5L
        whenever(mockAssetRepository.getDistributionAssetsForExistingFilesystem(filesystem))
                .thenReturn(singleAssetList)
        whenever(mockFilesystemUtility.areAllRequiredAssetsPresent("${filesystem.id}", singleAssetList))
                .thenReturn(true)
        whenever(mockTimeUtility.getCurrentTimeMillis()).thenReturn(filesystemUpdateTime)

        val updateTimeIsGreaterThanLastFilesystemUpdate = filesystem.lastUpdated + 1
        whenever(mockAssetRepository.getLastDistributionUpdate(filesystem.distributionType))
                .thenReturn(updateTimeIsGreaterThanLastFilesystemUpdate)

        runBlocking { sessionFsm.submitEvent(VerifyFilesystemAssets(filesystem), this) }

        val updatedFilesystem = filesystem
        updatedFilesystem.lastUpdated = filesystemUpdateTime
        verify(mockFilesystemUtility).removeRootfsFilesFromFilesystem("${filesystem.id}")
        verify(mockFilesystemUtility).copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType)
        verify(mockFilesystemDao).updateFilesystem(filesystem)
        verify(mockStateObserver).onChanged(VerifyingFilesystemAssets)
        verify(mockStateObserver).onChanged(FilesystemHasRequiredAssets)
    }

    @Test
    fun `State is DistributionCopyFailed if filesystem assets are not up to date and copying fails`() {
        sessionFsm.setState(ExtractionSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.getDistributionAssetsForExistingFilesystem(filesystem))
                .thenReturn(singleAssetList)
        whenever(mockFilesystemUtility.areAllRequiredAssetsPresent("${filesystem.id}", singleAssetList))
                .thenReturn(true)

        val updateTimeIsGreaterThanLastFilesystemUpdate = filesystem.lastUpdated + 1
        whenever(mockAssetRepository.getLastDistributionUpdate(filesystem.distributionType))
                .thenReturn(updateTimeIsGreaterThanLastFilesystemUpdate)
        whenever(mockFilesystemUtility.copyAssetsToFilesystem("${filesystem.id}", filesystem.distributionType))
                .thenThrow(Exception::class.java)

        runBlocking { sessionFsm.submitEvent(VerifyFilesystemAssets(filesystem), this) }

        verify(mockStateObserver).onChanged(VerifyingFilesystemAssets)
        verify(mockStateObserver).onChanged(DistributionCopyFailed)
    }

    @Test
    fun `State is FilesystemIsMissingRequiredAssets if any assets are missing`() {
        sessionFsm.setState(ExtractionSucceeded)
        sessionFsm.getState().observeForever(mockStateObserver)

        whenever(mockAssetRepository.getDistributionAssetsForExistingFilesystem(filesystem))
                .thenReturn(singleAssetList)
        whenever(mockFilesystemUtility.areAllRequiredAssetsPresent("${filesystem.id}", singleAssetList))
                .thenReturn(false)

        runBlocking { sessionFsm.submitEvent(VerifyFilesystemAssets(filesystem), this) }

        verify(mockStateObserver).onChanged(VerifyingFilesystemAssets)
        verify(mockStateObserver).onChanged(FilesystemIsMissingRequiredAssets)
    }
}