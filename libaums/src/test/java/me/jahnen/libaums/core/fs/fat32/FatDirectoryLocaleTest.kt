package me.jahnen.libaums.core.fs.fat32

import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.file.FileBlockDeviceDriver
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * FAT names are matched case-insensitively. The in-memory name index (lfnMap) must fold case
 * with a fixed locale, otherwise on a Turkish/Azeri device "FILE".lowercase() yields the dotless
 * "fıle" and a case-insensitive duplicate like "file" is not detected -> two conflicting entries
 * for the same FAT name. Case folding must be locale-independent (Locale.ROOT).
 */
class FatDirectoryLocaleTest {

    private lateinit var image: File

    @Before
    fun setUp() {
        image = File.createTempFile("libaums_locale", ".img").apply { deleteOnExit() }
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
    fun caseInsensitiveDuplicateDetectedUnderTurkishLocale() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            val fs = mount()
            fs.rootDirectory.createFile("FILE.TXT")
            try {
                fs.rootDirectory.createFile("file.txt")
                fail("case-insensitive duplicate 'file.txt' should be rejected regardless of locale")
            } catch (e: IOException) {
                // expected: "Item already exists!"
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
