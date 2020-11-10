package tech.ula.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UlaFiles(
    val filesDir: File,
    val scopedDir: File,
    val libDir: File,
    private val symlinker: Symlinker = Symlinker()
) {

    val libLinkDir: File = File(filesDir, "lib")
    val supportDir: File = File(filesDir, "support")

    val busybox = File(libDir, "busybox")
    val proot = File(libDir, "proot")

    internal val supportDirFileRequirements = listOf(
            "addNonRootUser.sh",
            "busybox_static",
            "compressFilesystem.sh",
            "extractFilesystem.sh",
            "execInProot.sh",
            "isServerInProcTree.sh",
            "killProcTree.sh",
            "stat4",
            "stat8",
            "uptime"
    )

    internal val libDirectorySymlinkMapping = listOf(
            "libc++_shared.so" to "libcppshared",
            "libcrypto.so.1.1" to "libcrypto.1.1",
            "libleveldb.so.1" to "libleveldb.1",
            "libtalloc.so.2" to "libtalloc.2",
            "libtermux-auth.so" to "libtermuxauth",
            "libutil.so" to "libutil"
    )

    suspend fun setupSupportDir() = withContext(Dispatchers.IO) {
        supportDir.mkdirs()

        supportDirFileRequirements.forEach { filename ->
            val assetFile = File(libDir, filename)
            val target = File(supportDir, filename)
            assetFile.copyTo(target, overwrite = true)
            makePermissionsUsable(supportDir.path, filename)
        }
    }

    suspend fun setupLinks() = withContext(Dispatchers.IO) {
        libLinkDir.mkdirs()

        libDirectorySymlinkMapping.forEach { (requiredLinkName, actualLibName) ->
            val libFile = File(libDir, actualLibName)
            val linkFile = File(libLinkDir, requiredLinkName)
            if (!libFile.exists()) throw NoSuchFileException(libFile)
            linkFile.delete()
            symlinker.createSymlink(libFile.path, linkFile.path)
        }
    }
}