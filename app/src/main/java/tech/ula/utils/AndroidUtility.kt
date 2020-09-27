package tech.ula.utils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.support.v4.content.ContextCompat
import android.util.DisplayMetrics
import android.view.WindowManager
import org.acra.ACRA
import tech.ula.R
import tech.ula.model.entities.App
import tech.ula.model.entities.Asset
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

fun makePermissionsUsable(containingDirectoryPath: String, filename: String) {
    val commandToRun = arrayListOf("chmod", "0777", filename)

    val containingDirectory = File(containingDirectoryPath)
    containingDirectory.mkdirs()

    val pb = ProcessBuilder(commandToRun)
    pb.directory(containingDirectory)

    val process = pb.start()
    process.waitFor()
}

fun arePermissionsGranted(context: Context): Boolean {
    return (ContextCompat.checkSelfPermission(context,
            Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&

            ContextCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
}

fun displayGenericErrorDialog(activity: Activity, titleId: Int, messageId: Int, callback: (() -> Unit) = {}) {
    AlertDialog.Builder(activity)
            .setTitle(titleId)
            .setMessage(messageId)
            .setPositiveButton(R.string.button_ok) {
                dialog, _ ->
                callback()
                dialog.dismiss()
            }
            .create().show()
}

// Add or change asset types as needed for testing and staggered releases.
fun getBranchToDownloadAssetsFrom(assetType: String): String {
    return when (assetType) {
        "support" -> "staging"
        "apps" -> "master"
        else -> "master"
    }
}

class StorageUtility(private val statFs: StatFs) {

    fun getAvailableStorageInMB(): Long {
        val bytesInMB = 1048576
        val bytesAvailable = statFs.blockSizeLong * statFs.availableBlocksLong
        return bytesAvailable / bytesInMB
    }
}

class DefaultPreferences(private val prefs: SharedPreferences) {

    fun getProotDebuggingEnabled(): Boolean {
        return prefs.getBoolean("pref_proot_debug_enabled", false)
    }

    fun getProotDebuggingLevel(): String {
        return prefs.getString("pref_proot_debug_level", "-1") ?: ""
    }

    fun getProotDebugLogLocation(): String {
        return prefs.getString("pref_proot_debug_log_location", "${Environment.getExternalStorageDirectory().path}/PRoot_Debug_Log") ?: ""
    }
}

class AssetPreferences(private val prefs: SharedPreferences) {

    private val versionString = "version"
    private val rootfsString = "rootfs"

    private val lowestVersion = "v0.0.0"
    fun getLatestDownloadVersion(repo: String): String {
        return prefs.getString("$repo-$versionString", lowestVersion) ?: lowestVersion
    }

    fun getLatestDownloadFilesystemVersion(repo: String): String {
        return prefs.getString("$repo-$rootfsString-$versionString", lowestVersion) ?: lowestVersion
    }

    fun setLatestDownloadVersion(repo: String, version: String) {
        with(prefs.edit()) {
            putString("$repo-$versionString", version)
            apply()
        }
    }

    fun setLatestDownloadFilesystemVersion(repo: String, version: String) {
        with(prefs.edit()) {
            putString("$repo-$rootfsString-$versionString", version)
            apply()
        }
    }

    private val downloadsAreInProgressKey = "downloadsAreInProgress"
    fun getDownloadsAreInProgress(): Boolean {
        return prefs.getBoolean(downloadsAreInProgressKey, false)
    }

    fun setDownloadsAreInProgress(inProgress: Boolean) {
        with(prefs.edit()) {
            putBoolean(downloadsAreInProgressKey, inProgress)
            apply()
        }
    }

    private val enqueuedDownloadsKey = "currentlyEnqueuedDownloads"
    fun getEnqueuedDownloads(): Set<Long> {
        val enqueuedDownloadsAsStrings = prefs.getStringSet(enqueuedDownloadsKey, setOf()) ?: setOf<String>()
        return enqueuedDownloadsAsStrings.map { it.toLong() }.toSet()
    }

    fun setEnqueuedDownloads(downloads: Set<Long>) {
        val enqueuedDownloadsAsStrings = downloads.map { it.toString() }.toSet()
        with(prefs.edit()) {
            putStringSet(enqueuedDownloadsKey, enqueuedDownloadsAsStrings)
            apply()
        }
    }

    fun clearEnqueuedDownloadsCache() {
        with(prefs.edit()) {
            remove(enqueuedDownloadsKey)
            apply()
        }
    }

    fun getCachedAssetList(assetType: String): List<Asset> {
        val entries = prefs.getStringSet(assetType, setOf()) ?: setOf()
        return entries.map { entry ->
            Asset(entry, assetType)
        }
    }

    fun setAssetList(assetType: String, assetList: List<Asset>) {
        val entries = assetList.map {
            it.name
        }.toSet()
        with(prefs.edit()) {
            putStringSet(assetType, entries)
            apply()
        }
    }
}

sealed class AppServiceTypePreference
object PreferenceHasNotBeenSelected : AppServiceTypePreference() {
    override fun toString(): String {
        return "unselected"
    }
}
object SshTypePreference : AppServiceTypePreference() {
    override fun toString(): String {
        return "ssh"
    }
}
object VncTypePreference : AppServiceTypePreference() {
    override fun toString(): String {
        return "vnc"
    }
}

object XsdlTypePreference : AppServiceTypePreference() {
    override fun toString(): String {
        return "xsdl"
    }
}

class AppsPreferences(private val prefs: SharedPreferences) {

    fun setAppServiceTypePreference(appName: String, serviceType: AppServiceTypePreference) {
        val prefAsString = when (serviceType) {
            is SshTypePreference -> "ssh"
            is VncTypePreference -> "vnc"
            is XsdlTypePreference -> "xsdl"
            else -> "unselected"
        }
        with(prefs.edit()) {
            putString(appName, prefAsString)
            apply()
        }
    }

    fun getAppServiceTypePreference(app: App): AppServiceTypePreference {
        val pref = prefs.getString(app.name, "") ?: ""

        return when {
            pref.toLowerCase() == "ssh" || (app.supportsCli && !app.supportsGui) -> SshTypePreference
            pref.toLowerCase() == "xsdl" -> XsdlTypePreference
            pref.toLowerCase() == "vnc" -> VncTypePreference
            else -> PreferenceHasNotBeenSelected
        }
    }

    fun setDistributionsList(distributionList: Set<String>) {
        with(prefs.edit()) {
            putStringSet("distributionsList", distributionList)
            apply()
        }
    }

    fun getDistributionsList(): Set<String> {
        return prefs.getStringSet("distributionsList", setOf()) ?: setOf()
    }
}

class BuildWrapper {
    private fun getSupportedAbis(): Array<String> {
        return Build.SUPPORTED_ABIS
    }

    fun getArchType(): String {
        val supportedABIS = this.getSupportedAbis()
                .map {
                    translateABI(it)
                }
                .filter {
                    isSupported(it)
                }
        return if (supportedABIS.size == 1 && supportedABIS[0] == "") {
            val exception = IllegalStateException("No supported ABI!")
            AcraWrapper().logException(exception)
            throw exception
        } else {
            supportedABIS[0]
        }
    }

    private fun isSupported(abi: String): Boolean {
        val supportedABIs = listOf("arm64", "arm", "x86_64", "x86")
        return supportedABIs.contains(abi)
    }

    private fun translateABI(abi: String): String {
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> ""
        }
    }
}

class ConnectionUtility {
    @Throws(Exception::class)
    fun getUrlInputStream(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return conn.inputStream
    }
}

class DownloadManagerWrapper(private val downloadManager: DownloadManager) {
    fun generateDownloadRequest(url: String, destination: String): DownloadManager.Request {
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        request.setTitle(destination)
        request.setDescription("Downloading ${destination.substringAfterLast("-")}.")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination)
        return request
    }

    fun enqueue(request: DownloadManager.Request): Long {
        return downloadManager.enqueue(request)
    }

    private fun generateQuery(id: Long): DownloadManager.Query {
        val query = DownloadManager.Query()
        query.setFilterById(id)
        return query
    }

    private fun generateCursor(query: DownloadManager.Query): Cursor {
        return downloadManager.query(query)
    }

    fun downloadHasSucceeded(id: Long): Boolean {
        val query = generateQuery(id)
        val cursor = generateCursor(query)
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
        return false
    }

    fun downloadHasFailed(id: Long): Boolean {
        val query = generateQuery(id)
        val cursor = generateCursor(query)
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_FAILED
        }
        return false
    }

    fun getDownloadFailureReason(id: Long): String {
        val query = generateQuery(id)
        val cursor = generateCursor(query)
        if (cursor.moveToFirst()) {
            val status: Int = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
            return "Reason: " + when (status) {
                in 100..500 -> "Http Error: $status"
                1008 -> "Cannot resume download."
                1007 -> "No external devices found."
                1009 -> "Destination already exists."
                1001 -> "Unknown file error."
                1004 -> "HTTP data processing error."
                1006 -> "Insufficient external storage space."
                1005 -> "Too many redirects."
                1002 -> "Unhandled HTTP response code."
                1000 -> "Unknown error."
                else -> "Unknown failure reason."
            }
        }
        return "No known reason for failure."
    }

    fun getDownloadsDirectory(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
}

class LocalFileLocator(private val applicationFilesDir: String, private val resources: Resources) {
    fun findIconUri(type: String): Uri {
        val icon =
                File("$applicationFilesDir/apps/$type/$type.png")
        if (icon.exists()) return Uri.fromFile(icon)
        return getDefaultIconUri()
    }

    private fun getDefaultIconUri(): Uri {
        val resId = R.mipmap.ic_launcher_foreground
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE +
                "://" + resources.getResourcePackageName(resId) + '/' +
                resources.getResourceTypeName(resId) + '/' +
                resources.getResourceEntryName(resId))
    }

    fun findAppDescription(appName: String): String {
        val appDescriptionFile =
                File("$applicationFilesDir/apps/$appName/$appName.txt")
        if (!appDescriptionFile.exists()) {
            return resources.getString(R.string.error_app_description_not_found)
        }
        return appDescriptionFile.readText()
    }
}

class AcraWrapper {
    fun putCustomString(key: String, value: String) {
        ACRA.getErrorReporter().putCustomData(key, value)
    }

    fun logException(err: Exception): Exception {
        val topOfStackTrace = err.stackTrace.first()
        val key = "Exception: ${topOfStackTrace.fileName}"
        val value = "${topOfStackTrace.lineNumber}"
        ACRA.getErrorReporter().putCustomData(key, value)
        return err
    }

    fun silentlySendIllegalStateReport() {
        ACRA.getErrorReporter().handleSilentException(IllegalStateException())
    }
}

class DeviceDimensions {
    private var width = 720
    private var height = 1480

    fun getDeviceDimensions(windowManager: WindowManager, displayMetrics: DisplayMetrics) {
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        width = displayMetrics.widthPixels
        height = displayMetrics.heightPixels
    }

    fun getGeometry(): String {
        return when (height > width) {
            true -> "${height}x$width"
            false -> "${width}x$height"
        }
    }
}

class UserFeedbackUtility(private val prefs: SharedPreferences) {
    private val numberOfTimesOpenedKey = "numberOfTimesOpened"
    private val userGaveFeedbackKey = "userGaveFeedback"
    private val dateTimeFirstOpenKey = "dateTimeFirstOpen"
    private val millisecondsInThreeDays = 259200000L
    private val minimumNumberOfOpensBeforeReviewRequest = 15

    fun askingForFeedbackIsAppropriate(): Boolean {
        return getIsSufficientTimeElapsedSinceFirstOpen() && numberOfTimesOpenedIsGreaterThanThreshold() && !getUserGaveFeedback()
    }

    fun incrementNumberOfTimesOpened() {
        with(prefs.edit()) {
            val numberTimesOpened = prefs.getInt(numberOfTimesOpenedKey, 1)
            if (numberTimesOpened == 1) putLong(dateTimeFirstOpenKey, System.currentTimeMillis())
            putInt(numberOfTimesOpenedKey, numberTimesOpened + 1)
            apply()
        }
    }

    fun userHasGivenFeedback() {
        with(prefs.edit()) {
            putBoolean(userGaveFeedbackKey, true)
            apply()
        }
    }

    private fun getUserGaveFeedback(): Boolean {
        return prefs.getBoolean(userGaveFeedbackKey, false)
    }

    private fun getIsSufficientTimeElapsedSinceFirstOpen(): Boolean {
        val dateTimeFirstOpened = prefs.getLong(dateTimeFirstOpenKey, 0L)
        val dateTimeWithSufficientTimeElapsed = dateTimeFirstOpened + millisecondsInThreeDays

        return (System.currentTimeMillis() > dateTimeWithSufficientTimeElapsed)
    }

    private fun numberOfTimesOpenedIsGreaterThanThreshold(): Boolean {
        val numberTimesOpened = prefs.getInt(numberOfTimesOpenedKey, 1)
        return numberTimesOpened > minimumNumberOfOpensBeforeReviewRequest
    }
}