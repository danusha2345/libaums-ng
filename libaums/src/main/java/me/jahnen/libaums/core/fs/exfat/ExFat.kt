package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.partition.PartitionTypes
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only exFAT file system. Supports mounting, directory listing and reading file contents.
 * Writing is not supported yet (the mutating [UsbFile] operations throw).
 */
class ExFat private constructor(
    private val blockDevice: BlockDeviceDriver,
    internal val bootSector: ExFatBootSector
) : FileSystem {

    private val root = ExFatDirectory.createRoot(this, bootSector.rootDirCluster)

    override val rootDirectory: UsbFile
        get() = root

    override val volumeLabel: String
        get() {
            root.listFiles() // ensure the root is parsed so the label is populated
            return root.volumeLabel
        }

    override val capacity: Long
        get() = bootSector.clusterCount * bootSector.bytesPerCluster.toLong()

    // Free/occupied space requires walking the allocation bitmap; not tracked in the read-only phase.
    override val occupiedSpace: Long
        get() = -1

    override val freeSpace: Long
        get() = -1

    override val chunkSize: Int
        get() = bootSector.bytesPerCluster

    override val type: Int
        get() = PartitionTypes.NTFS_EXFAT

    /** Reads a single 32-bit little-endian FAT entry for [cluster]. */
    @Throws(IOException::class)
    private fun fatEntry(cluster: Long): Long {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        blockDevice.read(bootSector.fatByteOffset + cluster * 4, buffer)
        buffer.clear()
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

    /** Follows the FAT cluster chain starting at [start] until the end-of-chain marker. */
    @Throws(IOException::class)
    internal fun followChain(start: Long): List<Long> {
        val clusters = ArrayList<Long>()
        var cluster = start
        while (cluster in ExFatBootSector.FIRST_CLUSTER until ExFatBootSector.END_OF_CHAIN) {
            clusters.add(cluster)
            if (clusters.size > bootSector.clusterCount + 1) break // guard against a corrupt loop
            cluster = fatEntry(cluster)
        }
        return clusters
    }

    /** Cluster indices for a region, honouring the NoFatChain (contiguous) flag. */
    @Throws(IOException::class)
    private fun clustersFor(firstCluster: Long, dataLength: Long, noFatChain: Boolean): List<Long> {
        if (!noFatChain) return followChain(firstCluster)
        val n = (dataLength + bootSector.bytesPerCluster - 1) / bootSector.bytesPerCluster
        return (0 until n).map { firstCluster + it }
    }

    @Throws(IOException::class)
    private fun readCluster(cluster: Long): ByteArray {
        val buffer = ByteBuffer.allocate(bootSector.bytesPerCluster)
        blockDevice.read(bootSector.clusterToByteOffset(cluster), buffer)
        return buffer.array()
    }

    /** Reads all raw bytes of a directory (root: full FAT chain; sub-dir: per its flags). */
    @Throws(IOException::class)
    internal fun readDirectoryBytes(firstCluster: Long, dataLength: Long, noFatChain: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        for (cluster in clustersFor(firstCluster, dataLength, noFatChain)) {
            out.write(readCluster(cluster))
        }
        return out.toByteArray()
    }

    /** Reads `destination.remaining()` bytes of a file starting at byte [offset]. */
    @Throws(IOException::class)
    internal fun readFile(firstCluster: Long, dataLength: Long, noFatChain: Boolean, offset: Long, destination: ByteBuffer) {
        val clusterSize = bootSector.bytesPerCluster
        val clusters = clustersFor(firstCluster, dataLength, noFatChain)
        var pos = offset
        while (destination.remaining() > 0) {
            val index = (pos / clusterSize).toInt()
            if (index >= clusters.size) break
            val offsetInCluster = (pos % clusterSize).toInt()
            val toRead = minOf(clusterSize - offsetInCluster, destination.remaining())
            val chunk = ByteArray(toRead)
            blockDevice.read(bootSector.clusterToByteOffset(clusters[index]) + offsetInCluster, ByteBuffer.wrap(chunk))
            destination.put(chunk)
            pos += toRead
        }
    }

    companion object {
        @Throws(IOException::class)
        fun read(blockDevice: BlockDeviceDriver): ExFat? {
            val bootSector = ExFatBootSector.read(blockDevice) ?: return null
            return ExFat(blockDevice, bootSector)
        }
    }
}
