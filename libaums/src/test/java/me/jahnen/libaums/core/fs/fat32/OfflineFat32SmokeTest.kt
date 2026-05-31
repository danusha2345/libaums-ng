package me.jahnen.libaums.core.fs.fat32

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Self-contained, offline FAT32 test. Unlike the existing contract tests, this does NOT
 * download a multi-MB image over the network: it ships a tiny gzipped blank FAT32 image
 * (src/test/resources/fat32.img.gz, made with `mkfs.vfat -F 32`) and drives it through
 * the real [FileBlockDeviceDriver]. Run with: `--tests "*OfflineFat32SmokeTest"`.
 */
class OfflineFat32SmokeTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_offline_fat32", ".img").apply { deleteOnExit() }
        (javaClass.getResourceAsStream("/fat32.img.gz")
            ?: error("missing test fixture /fat32.img.gz")).use { gz ->
            GZIPInputStream(gz).use { input ->
                image.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /** Mount the fixture as a FAT32 filesystem (image has no partition table -> offset 0). */
    private fun mount(): Fat32FileSystem {
        val blockDevice = ByteBlockDevice(FileBlockDeviceDriver(image, 0, 512))
        blockDevice.init()
        return Fat32FileSystem.read(blockDevice)
            ?: error("fixture not recognised as FAT32")
    }

    @Test
    fun mountsBlankFat32() {
        val fs = mount()
        assertTrue("capacity should be positive", fs.capacity > 0)
        assertEquals("blank image should have empty root", 0, fs.rootDirectory.listFiles().size)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val payload = "libaums offline harness ".repeat(64).toByteArray()

        // write a file, then verify the reported length equals the bytes written (cf. issue #413)
        mount().let { fs ->
            val file = fs.rootDirectory.createFile("hello.txt")
            UsbFileOutputStream(file).use { it.write(payload) }
            assertEquals("length after write", payload.size.toLong(), file.length)
        }

        // re-mount (forces a fresh read from disk) and read the content back
        mount().let { fs ->
            val file = fs.rootDirectory.listFiles().single { it.name == "hello.txt" }
            assertEquals("length after remount", payload.size.toLong(), file.length)
            val read = UsbFileInputStream(file).use { it.readBytes() }
            assertArrayEquals("content round-trips", payload, read)
        }
    }
}
