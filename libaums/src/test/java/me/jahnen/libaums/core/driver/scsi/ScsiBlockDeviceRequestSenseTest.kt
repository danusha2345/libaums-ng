package me.jahnen.libaums.core.driver.scsi

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression test for issue #410: [ScsiBlockDevice.requestSense] crashed with
 * `IllegalArgumentException: newLimit > capacity: (36 > 18)` when a device reported an
 * "additional sense length" implying a sense response larger than the 18 bytes we allocate.
 * The dynamic-length recompute in `transferOneCommand` must be clamped to the buffer capacity.
 */
class ScsiBlockDeviceRequestSenseTest {

    /**
     * Fake transport that fails the first command (INQUIRY) so init() issues a REQUEST SENSE,
     * then answers the sense data phase with an oversized additionalSenseLength (28 -> total 36).
     * Endpoint/interface accessors throw because this code path never touches them.
     */
    private class FakeSenseUsbCommunication(private val additionalSenseLength: Int) : UsbCommunication {
        private var lastOpcode: Byte = 0
        private var lastTag: Int = 0
        private var nextInIsCsw = false

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            val view = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val size = src.remaining()
            lastTag = view.getInt(src.position() + 4)    // CBW tag: LE int at offset 4
            lastOpcode = view.get(src.position() + 15)   // SCSI opcode right after the 15-byte CBW header
            src.position(src.limit())
            nextInIsCsw = false                          // the next IN transfer is the data phase
            return size
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            if (!nextInIsCsw) {
                nextInIsCsw = true
                val data = when (lastOpcode.toInt()) {
                    0x12 -> ByteArray(dest.remaining())  // INQUIRY: zero-filled response
                    0x03 -> senseData()                  // REQUEST SENSE response
                    else -> throw IllegalStateException("unexpected opcode $lastOpcode")
                }
                dest.put(data)
                return data.size
            }
            nextInIsCsw = false
            val status = if (lastOpcode.toInt() == 0x12) 1 else 0   // fail INQUIRY, pass SENSE
            dest.put(csw(lastTag, status))
            return 13
        }

        private fun senseData(): ByteArray = ByteArray(18).apply {
            this[0] = 0x70.toByte()                      // error code: current, fixed-format sense
            this[2] = 0x02                               // sense key = NOT READY
            this[7] = additionalSenseLength.toByte()     // dynamic size = 8 + this; 28 -> 36 > 18
        }

        private fun csw(tag: Int, status: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x53425355)                       // dCSWSignature "USBS"
                putInt(tag)
                putInt(0)                                // residue
                put(status.toByte())
            }.array()

        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int = 0
        override fun resetDevice() {}
        override fun clearFeatureHalt(endpoint: UsbEndpoint) {}
        override fun close() {}
        override val inEndpoint: UsbEndpoint get() = error("endpoint not used in this test")
        override val outEndpoint: UsbEndpoint get() = error("endpoint not used in this test")
        override val usbInterface: UsbInterface get() = error("interface not used in this test")
    }

    @Test
    fun requestSenseDoesNotOverflowOnLongerAdditionalSenseLength() {
        val device = ScsiBlockDevice(FakeSenseUsbCommunication(additionalSenseLength = 28), lun = 0)
        try {
            device.init()
            fail("init() was expected to surface the device's sense error")
        } catch (e: IllegalArgumentException) {
            fail("issue #410 regression: sense buffer overflowed (${e.message})")
        } catch (e: IOException) {
            // expected: the sense response is parsed and surfaced as an IOException / SenseException
        }
    }

    @Test
    fun requestSenseHandlesAdditionalSenseLengthAbove127() {
        // additionalSenseLength is an unsigned byte; values >= 128 must not be read as negative.
        val device = ScsiBlockDevice(FakeSenseUsbCommunication(additionalSenseLength = 0x88), lun = 0)
        try {
            device.init()
            fail("init() was expected to surface the device's sense error")
        } catch (e: IllegalArgumentException) {
            fail("sense length read as signed -> bad limit (${e.message})")
        } catch (e: IOException) {
            // expected: the sense response is parsed and surfaced as an IOException / SenseException
        }
    }
}
