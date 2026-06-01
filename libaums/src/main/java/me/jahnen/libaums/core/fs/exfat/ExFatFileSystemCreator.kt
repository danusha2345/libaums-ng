package me.jahnen.libaums.core.fs.exfat

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.partition.PartitionTableEntry
import java.io.IOException

/** Detects and mounts a (read-only) exFAT file system. */
class ExFatFileSystemCreator : FileSystemCreator {
    @Throws(IOException::class)
    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? =
        ExFat.read(blockDevice)
}
