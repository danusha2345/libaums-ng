package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.fs.AbstractUsbFile
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

/** A read-only file on an exFAT volume. */
internal class ExFatFile(
    private val fs: ExFat,
    private val fileName: String,
    private val firstCluster: Long,
    private val dataLength: Long,
    private val noFatChain: Boolean,
    override val parent: UsbFile?
) : AbstractUsbFile() {

    override val isDirectory: Boolean get() = false
    override val isRoot: Boolean get() = false

    override var name: String
        get() = fileName
        set(_) = throw UnsupportedOperationException("read-only exFAT")

    override var length: Long
        get() = dataLength
        set(_) = throw UnsupportedOperationException("read-only exFAT")

    override fun createdAt(): Long = 0
    override fun lastModified(): Long = 0
    override fun lastAccessed(): Long = 0

    override fun list(): Array<String> = throw UnsupportedOperationException("This is a file!")
    override fun listFiles(): Array<UsbFile> = throw UnsupportedOperationException("This is a file!")

    @Throws(IOException::class)
    override fun read(offset: Long, destination: ByteBuffer) =
        fs.readFile(firstCluster, dataLength, noFatChain, offset, destination)

    override fun write(offset: Long, source: ByteBuffer) = throw UnsupportedOperationException("read-only exFAT")
    override fun flush() {}
    override fun close() {}
    override fun createDirectory(name: String): UsbFile = throw UnsupportedOperationException("read-only exFAT")
    override fun createFile(name: String): UsbFile = throw UnsupportedOperationException("read-only exFAT")
    override fun moveTo(destination: UsbFile) = throw UnsupportedOperationException("read-only exFAT")
    override fun delete() = throw UnsupportedOperationException("read-only exFAT")
}
