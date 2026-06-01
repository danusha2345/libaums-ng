package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Read-only exFAT, phase 1a. Drives an authentic empty image made by mkfs.exfat
 * (src/test/resources/exfat.img.gz): the volume mounts, the label and capacity are read from
 * the boot sector, and the root directory contains no user files (only the system entries).
 */
class ExFatReadTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_exfat", ".img").apply { deleteOnExit() }
        (javaClass.getResourceAsStream("/exfat.img.gz")
            ?: error("missing test fixture /exfat.img.gz")).use { gz ->
            GZIPInputStream(gz).use { input -> image.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun mount(): ExFat {
        val blockDevice = ByteBlockDevice(FileBlockDeviceDriver(image, 0, 512))
        blockDevice.init()
        return ExFat.read(blockDevice) ?: error("fixture not recognised as exFAT")
    }

    @Test
    fun mountsEmptyExFatVolume() {
        val fs = mount()
        assertEquals("LIBAUMSX", fs.volumeLabel)
        assertTrue("capacity should be positive", fs.capacity > 0)
        assertEquals("a freshly formatted volume has no user files", 0, fs.rootDirectory.listFiles().size)
    }
}
