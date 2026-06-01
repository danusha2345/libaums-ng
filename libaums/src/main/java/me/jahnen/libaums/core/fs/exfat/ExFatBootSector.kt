package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the exFAT Main Boot Sector (sector 0). Only the fields needed for reading are kept.
 * See the exFAT specification, "Main Boot Sector".
 */
internal class ExFatBootSector private constructor(buffer: ByteBuffer) {

    val volumeLengthSectors: Long
    val fatOffsetSectors: Long
    val fatLengthSectors: Long
    val clusterHeapOffsetSectors: Long
    val clusterCount: Long
    val rootDirCluster: Long
    val bytesPerSector: Int
    val sectorsPerCluster: Int

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        volumeLengthSectors = buffer.getLong(0x48)
        fatOffsetSectors = buffer.getInt(0x50).toLong() and 0xFFFFFFFFL
        fatLengthSectors = buffer.getInt(0x54).toLong() and 0xFFFFFFFFL
        clusterHeapOffsetSectors = buffer.getInt(0x58).toLong() and 0xFFFFFFFFL
        clusterCount = buffer.getInt(0x5C).toLong() and 0xFFFFFFFFL
        rootDirCluster = buffer.getInt(0x60).toLong() and 0xFFFFFFFFL
        bytesPerSector = 1 shl (buffer.get(0x6C).toInt() and 0xFF)
        sectorsPerCluster = 1 shl (buffer.get(0x6D).toInt() and 0xFF)
    }

    val bytesPerCluster: Int
        get() = bytesPerSector * sectorsPerCluster

    val fatByteOffset: Long
        get() = fatOffsetSectors * bytesPerSector

    /** Byte offset of a data-area cluster (cluster index is >= [FIRST_CLUSTER]). */
    fun clusterToByteOffset(cluster: Long): Long =
        (clusterHeapOffsetSectors + (cluster - FIRST_CLUSTER) * sectorsPerCluster) * bytesPerSector

    companion object {
        const val FIRST_CLUSTER = 2L

        /** End-of-chain marker: FAT entries >= this value terminate a cluster chain. */
        const val END_OF_CHAIN = 0xFFFFFFF8L

        private const val SIGNATURE = "EXFAT   "

        @Throws(IOException::class)
        fun read(blockDevice: BlockDeviceDriver): ExFatBootSector? {
            val buffer = ByteBuffer.allocate(512)
            blockDevice.read(0, buffer)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val name = ByteArray(8) { buffer.get(3 + it) }
            if (String(name, Charsets.US_ASCII) != SIGNATURE) return null
            if (buffer.getShort(0x1FE) != 0xAA55.toShort()) return null

            return ExFatBootSector(buffer)
        }
    }
}
