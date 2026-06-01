package me.jahnen.libaums.core.fs.fat32

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Issue #357: a copied file must keep the source timestamp instead of "now". The fix exposes
 * UsbFile.setLastModified/setCreatedAt/setLastAccessed; this verifies the value survives a remount.
 */
class Fat32TimestampTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_ts", ".img").apply { deleteOnExit() }
        (javaClass.getResourceAsStream("/fat32.img.gz")
            ?: error("missing test fixture /fat32.img.gz")).use { gz ->
            GZIPInputStream(gz).use { input -> image.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun mount(): Fat32FileSystem {
        val blockDevice = ByteBlockDevice(FileBlockDeviceDriver(image, 0, 512))
        blockDevice.init()
        return Fat32FileSystem.read(blockDevice) ?: error("fixture not recognised as FAT32")
    }

    @Test
    fun lastModifiedIsPreservedAcrossRemount() {
        val savedTz = FileSystemFactory.timeZone
        FileSystemFactory.timeZone = TimeZone.getTimeZone("UTC")
        try {
            val srcTime = 1_577_836_800_000L // 2020-01-01T00:00:00Z, aligned to FAT's 2s resolution

            mount().let { fs ->
                val file = fs.rootDirectory.createFile("ts.txt")
                UsbFileOutputStream(file).use { it.write("x".toByteArray()) }
                file.setLastModified(srcTime)
            }

            mount().let { fs ->
                val file = fs.rootDirectory.listFiles().single { it.name == "ts.txt" }
                val read = file.lastModified()
                // FAT modified-time has 2s resolution
                assertTrue("expected ~$srcTime, got $read", abs(read - srcTime) <= 2000)
            }
        } finally {
            FileSystemFactory.timeZone = savedTz
        }
    }
}
