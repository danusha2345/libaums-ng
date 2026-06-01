package me.jahnen.libaums.core.fs.fat32

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Offline correctness for truncation (shrinking length) and append, across a remount. */
class Fat32TruncateAppendTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_trunc", ".img").apply { deleteOnExit() }
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
    fun truncateKeepsPrefixAndLength() {
        val payload = ByteArray(300 * 1024) { (it % 97).toByte() }   // multi-cluster
        mount().let { fs ->
            val f = fs.rootDirectory.createFile("t.bin")
            UsbFileOutputStream(f).use { it.write(payload) }
            f.length = 100L          // truncate
            f.flush()
        }
        mount().let { fs ->
            val f = fs.rootDirectory.listFiles().single { it.name == "t.bin" }
            assertEquals(100L, f.length)
            assertArrayEquals(payload.copyOf(100), UsbFileInputStream(f).use { it.readBytes() })
        }
    }

    @Test
    fun appendExtendsContent() {
        mount().let { fs ->
            val f = fs.rootDirectory.createFile("a.bin")
            UsbFileOutputStream(f).use { it.write("AAAA".toByteArray()) }
            UsbFileOutputStream(f, /* append = */ true).use { it.write("BBBB".toByteArray()) }
        }
        mount().let { fs ->
            val f = fs.rootDirectory.listFiles().single { it.name == "a.bin" }
            assertArrayEquals("AAAABBBB".toByteArray(), UsbFileInputStream(f).use { it.readBytes() })
        }
    }
}
