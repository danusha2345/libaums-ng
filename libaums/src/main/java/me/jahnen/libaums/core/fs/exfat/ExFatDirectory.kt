package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A read-only directory on an exFAT volume. Parses the 32-byte directory entry sets
 * (File 0x85 + Stream Extension 0xC0 + File Name 0xC1) lazily on first listing.
 */
internal class ExFatDirectory(
    private val fs: ExFat,
    private val firstCluster: Long,
    private val dataLength: Long,
    private val noFatChain: Boolean,
    private val dirName: String,
    override val parent: UsbFile?,
    override val isRoot: Boolean
) : AbstractUsbFile() {

    /** Volume label from the root directory's Volume Label entry (0x83); empty otherwise. */
    var volumeLabel: String = ""
        private set

    private var children: List<UsbFile>? = null

    override val isDirectory: Boolean get() = true

    override var name: String
        get() = if (isRoot) "" else dirName
        set(_) = throw UnsupportedOperationException("read-only exFAT")

    override var length: Long
        get() = 0
        set(_) = throw UnsupportedOperationException("read-only exFAT")

    override fun createdAt(): Long = 0
    override fun lastModified(): Long = 0
    override fun lastAccessed(): Long = 0

    @Throws(IOException::class)
    override fun list(): Array<String> = listFiles().map { it.name }.toTypedArray()

    @Throws(IOException::class)
    override fun listFiles(): Array<UsbFile> {
        parse()
        return children!!.toTypedArray()
    }

    @Throws(IOException::class)
    private fun parse() {
        if (children != null) return

        val data = fs.readDirectoryBytes(firstCluster, dataLength, noFatChain)
        val result = ArrayList<UsbFile>()
        var i = 0
        while (i + ENTRY_SIZE <= data.size) {
            val type = data[i].toInt() and 0xFF
            when {
                type == ENTRY_END -> { i = data.size }                // end of directory
                type and IN_USE == 0 -> i += ENTRY_SIZE               // unused / deleted entry
                type == ENTRY_VOLUME_LABEL -> {
                    val charCount = data[i + 1].toInt() and 0xFF
                    volumeLabel = readUtf16(data, i + 2, charCount)
                    i += ENTRY_SIZE
                }
                type == ENTRY_FILE -> {
                    val secondaryCount = data[i + 1].toInt() and 0xFF
                    parseFileSet(data, i, secondaryCount)?.let { result.add(it) }
                    i += (1 + secondaryCount) * ENTRY_SIZE
                }
                else -> i += ENTRY_SIZE                                 // bitmap (0x81), up-case (0x82), etc.
            }
        }

        children = result
    }

    /** Builds a child from a File entry at [base] plus its Stream Extension and File Name entries. */
    private fun parseFileSet(data: ByteArray, base: Int, secondaryCount: Int): UsbFile? {
        val attributes = leShort(data, base + 4)
        val isDir = attributes and ATTR_DIRECTORY != 0

        val streamOffset = base + ENTRY_SIZE
        if (streamOffset + ENTRY_SIZE > data.size) return null
        if (data[streamOffset].toInt() and 0xFF != ENTRY_STREAM_EXT) return null

        val flags = data[streamOffset + 1].toInt() and 0xFF
        val childNoFatChain = flags and FLAG_NO_FAT_CHAIN != 0
        val nameLength = data[streamOffset + 3].toInt() and 0xFF
        val childFirstCluster = leInt(data, streamOffset + 0x14)
        val childDataLength = leLong(data, streamOffset + 0x18)

        val sb = StringBuilder()
        var entryOffset = streamOffset + ENTRY_SIZE
        var collected = 0
        // remaining secondaries after the stream-extension entry are File Name entries
        for (n in 0 until (secondaryCount - 1)) {
            if (entryOffset + ENTRY_SIZE > data.size) break
            if (data[entryOffset].toInt() and 0xFF != ENTRY_FILE_NAME) break
            val take = minOf(NAME_CHARS_PER_ENTRY, nameLength - collected)
            if (take <= 0) break
            sb.append(readUtf16(data, entryOffset + 2, take))
            collected += take
            entryOffset += ENTRY_SIZE
        }
        val childName = sb.toString()

        return if (isDir) {
            ExFatDirectory(fs, childFirstCluster, childDataLength, childNoFatChain, childName, this, false)
        } else {
            ExFatFile(fs, childName, childFirstCluster, childDataLength, childNoFatChain, this)
        }
    }

    override fun read(offset: Long, destination: ByteBuffer) = throw UnsupportedOperationException("This is a directory!")
    override fun write(offset: Long, source: ByteBuffer) = throw UnsupportedOperationException("read-only exFAT")
    override fun flush() {}
    override fun close() {}
    override fun createDirectory(name: String): UsbFile = throw UnsupportedOperationException("read-only exFAT")
    override fun createFile(name: String): UsbFile = throw UnsupportedOperationException("read-only exFAT")
    override fun moveTo(destination: UsbFile) = throw UnsupportedOperationException("read-only exFAT")
    override fun delete() = throw UnsupportedOperationException("read-only exFAT")

    companion object {
        private const val ENTRY_SIZE = 32
        private const val IN_USE = 0x80
        private const val ENTRY_END = 0x00
        private const val ENTRY_VOLUME_LABEL = 0x83
        private const val ENTRY_FILE = 0x85
        private const val ENTRY_STREAM_EXT = 0xC0
        private const val ENTRY_FILE_NAME = 0xC1
        private const val ATTR_DIRECTORY = 0x10
        private const val FLAG_NO_FAT_CHAIN = 0x02
        private const val NAME_CHARS_PER_ENTRY = 15

        fun createRoot(fs: ExFat, rootDirCluster: Long): ExFatDirectory =
            ExFatDirectory(fs, rootDirCluster, 0L, false, "", null, true)

        private fun leShort(b: ByteArray, i: Int): Int =
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        private fun leInt(b: ByteArray, i: Int): Long {
            var v = 0L
            for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
            return v
        }

        private fun leLong(b: ByteArray, i: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
            return v
        }

        private fun readUtf16(b: ByteArray, off: Int, chars: Int): String {
            val sb = StringBuilder(chars)
            for (k in 0 until chars) {
                val lo = b[off + k * 2].toInt() and 0xFF
                val hi = b[off + k * 2 + 1].toInt() and 0xFF
                sb.append(((hi shl 8) or lo).toChar())
            }
            return sb.toString()
        }
    }
}
