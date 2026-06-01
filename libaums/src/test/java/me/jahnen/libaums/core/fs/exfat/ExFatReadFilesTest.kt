package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import me.jahnen.libaums.core.fs.UsbFileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Read-only exFAT, phase 1b. Drives an fsck-validated image (made by mkfs.exfat + the kernel
 * exFAT driver) containing /hello.txt, a multi-cluster /big.bin, and /sub/inner.txt.
 */
class ExFatReadFilesTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_exfat_pop", ".img").apply { deleteOnExit() }
        (javaClass.getResourceAsStream("/exfat_populated.img.gz")
            ?: error("missing test fixture /exfat_populated.img.gz")).use { gz ->
            GZIPInputStream(gz).use { input -> image.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun mount(): ExFat {
        val blockDevice = ByteBlockDevice(FileBlockDeviceDriver(image, 0, 512))
        blockDevice.init()
        return ExFat.read(blockDevice) ?: error("fixture not recognised as exFAT")
    }

    @Test
    fun listsRootEntries() {
        val names = mount().rootDirectory.listFiles().map { it.name }.toSet()
        assertEquals(setOf("hello.txt", "big.bin", "sub"), names)
    }

    @Test
    fun readsSmallFile() {
        val file = mount().rootDirectory.listFiles().single { it.name == "hello.txt" }
        assertEquals(21L, file.length)
        assertArrayEquals(
            "hello exfat read path".toByteArray(),
            UsbFileInputStream(file).use { it.readBytes() }
        )
    }

    @Test
    fun readsMultiClusterFile() {
        val expected = ByteArray(300 * 1024) { (it % 251).toByte() }
        val file = mount().rootDirectory.listFiles().single { it.name == "big.bin" }
        assertEquals(expected.size.toLong(), file.length)
        assertArrayEquals(expected, UsbFileInputStream(file).use { it.readBytes() })
    }

    @Test
    fun readsFileInSubdirectory() {
        val sub = mount().rootDirectory.listFiles().single { it.name == "sub" }
        assertEquals(true, sub.isDirectory)
        val inner = sub.listFiles().single { it.name == "inner.txt" }
        assertArrayEquals("inside subdir".toByteArray(), UsbFileInputStream(inner).use { it.readBytes() })
    }
}
