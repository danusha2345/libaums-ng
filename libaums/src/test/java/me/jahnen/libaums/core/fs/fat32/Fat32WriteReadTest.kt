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
 * Offline write/read correctness on a real FAT32 image (no network). Exercises the paths
 * that map to long-standing reports: subdirectory persistence across remount (cf. #33),
 * multi-cluster file allocation (FAT.alloc loop), and directory growth across clusters.
 */
class Fat32WriteReadTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_offline_fat32_wr", ".img").apply { deleteOnExit() }
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

    /** cf. #33: a directory (and a file inside it) created in one session must survive a remount. */
    @Test
    fun subdirectoryAndChildFilePersistAcrossRemount() {
        val payload = "child of a subdirectory".toByteArray()
        mount().let { fs ->
            val dir = fs.rootDirectory.createDirectory("logs")
            assertTrue(dir.isDirectory)
            val child = dir.createFile("a.txt")
            UsbFileOutputStream(child).use { it.write(payload) }
        }
        mount().let { fs ->
            val dir = fs.rootDirectory.listFiles().single { it.name == "logs" }
            assertTrue("logs must still be a directory", dir.isDirectory)
            val child = dir.listFiles().single { it.name == "a.txt" }
            assertArrayEquals(payload, UsbFileInputStream(child).use { it.readBytes() })
        }
    }

    /** Writing more than one cluster exercises the FAT.alloc chain-building loop. */
    @Test
    fun multiClusterFileRoundTrips() {
        val payload = ByteArray(300 * 1024) { (it % 97).toByte() }   // ~300 KiB, spans many clusters
        mount().let { fs ->
            val f = fs.rootDirectory.createFile("big.bin")
            UsbFileOutputStream(f).use { it.write(payload) }
            assertEquals(payload.size.toLong(), f.length)
        }
        mount().let { fs ->
            val f = fs.rootDirectory.listFiles().single { it.name == "big.bin" }
            assertEquals(payload.size.toLong(), f.length)
            assertArrayEquals(payload, UsbFileInputStream(f).use { it.readBytes() })
        }
    }

    /** Enough entries to push the root directory past a single cluster (LFN entries are large). */
    @Test
    fun manyEntriesGrowRootDirectory() {
        val count = 120
        mount().let { fs ->
            repeat(count) { i ->
                val f = fs.rootDirectory.createFile("a_reasonably_long_file_name_%03d.txt".format(i))
                UsbFileOutputStream(f).use { it.write("entry $i".toByteArray()) }
            }
        }
        mount().let { fs ->
            val names = fs.rootDirectory.listFiles().map { it.name }.toSet()
            assertEquals(count, names.size)
            repeat(count) { i ->
                assertTrue("missing entry $i", "a_reasonably_long_file_name_%03d.txt".format(i) in names)
            }
        }
    }
}
