package com.jarvis.assistant.tools

import android.content.Context
import android.hardware.ConsumerIrManager
import com.jarvis.assistant.agent.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Pure Kotlin IR pulse encoder supporting major TV & home-entertainment protocols
 * for Android's ConsumerIrManager (e.g., Xiaomi 15T Pro built-in IR blaster).
 */
object IrProtocolEncoder {

    data class IrSignal(
        val frequencyHz: Int,
        val patternUs: IntArray
    )

    private var rc5Toggle = false

    /**
     * Standard NEC 32-bit protocol (38 kHz).
     * Used by LG, Hisense, Xiaomi, and many soundbars/projectors.
     */
    fun encodeNec(address: Int, command: Int, invertedAddress: Int = address.inv() and 0xFF): IrSignal {
        val pulses = ArrayList<Int>(72)
        // Leader burst: 9000us mark, 4500us space
        pulses.add(9000)
        pulses.add(4500)

        val bytes = intArrayOf(
            address and 0xFF,
            invertedAddress and 0xFF,
            command and 0xFF,
            command.inv() and 0xFF
        )
        for (b in bytes) {
            for (bit in 0 until 8) {
                pulses.add(560)
                if (((b shr bit) and 1) == 1) {
                    pulses.add(1690)
                } else {
                    pulses.add(560)
                }
            }
        }
        // Final stop mark + inter-frame guard space
        pulses.add(560)
        pulses.add(40000)
        return IrSignal(frequencyHz = 38_000, patternUs = pulses.toIntArray())
    }

    /**
     * Samsung 32-bit NECx2 protocol (38 kHz): 4500/4500 leader and repeated address byte.
     */
    fun encodeSamsung32(address: Int, command: Int): IrSignal {
        val pulses = ArrayList<Int>(72)
        pulses.add(4500)
        pulses.add(4500)

        val bytes = intArrayOf(
            address and 0xFF,
            address and 0xFF,
            command and 0xFF,
            command.inv() and 0xFF
        )
        for (b in bytes) {
            for (bit in 0 until 8) {
                pulses.add(560)
                if (((b shr bit) and 1) == 1) {
                    pulses.add(1690)
                } else {
                    pulses.add(560)
                }
            }
        }
        pulses.add(560)
        pulses.add(45000)
        return IrSignal(frequencyHz = 38_000, patternUs = pulses.toIntArray())
    }

    /**
     * Sony SIRC 12-bit protocol (40 kHz). Repeats 3 frames per button press as per Sony spec.
     */
    fun encodeSony12(deviceAddress: Int, command: Int, frames: Int = 3): IrSignal {
        val pulses = ArrayList<Int>(frames * 28)
        val frameCount = frames.coerceIn(1, 4)
        repeat(frameCount) {
            var frameUs = 0
            pulses.add(2400)
            pulses.add(600)
            frameUs += 3000

            // 7-bit command LSB-first
            for (bit in 0 until 7) {
                val mark = if (((command shr bit) and 1) == 1) 1200 else 600
                pulses.add(mark)
                pulses.add(600)
                frameUs += mark + 600
            }
            // 5-bit device address LSB-first
            for (bit in 0 until 5) {
                val mark = if (((deviceAddress shr bit) and 1) == 1) 1200 else 600
                pulses.add(mark)
                pulses.add(600)
                frameUs += mark + 600
            }
            // Pad last space so frame period is ~45ms
            val pad = (45_000 - frameUs).coerceAtLeast(15_000)
            pulses[pulses.lastIndex] = pulses[pulses.lastIndex] + pad
        }
        return IrSignal(frequencyHz = 40_000, patternUs = pulses.toIntArray())
    }

    /**
     * Philips RC-5 Manchester biphase protocol (36 kHz, 889us half-bit).
     */
    fun encodeRc5(address: Int, command: Int, toggle: Boolean? = null): IrSignal {
        val tBit = if (toggle != null) {
            if (toggle) 1 else 0
        } else {
            rc5Toggle = !rc5Toggle
            if (rc5Toggle) 1 else 0
        }
        // 14 bits: S1(1), S2(1 for cmd < 64, 0 for extended), Toggle, 5-bit addr, 6-bit cmd
        val s2 = if ((command and 0x40) != 0) 0 else 1
        val word = (1 shl 13) or
            (s2 shl 12) or
            (tBit shl 11) or
            ((address and 0x1F) shl 6) or
            (command and 0x3F)

        // Half-bit stream: bit 1 = space(0) then mark(1); bit 0 = mark(1) then space(0)
        val halfBits = ArrayList<Boolean>(28)
        for (i in 13 downTo 0) {
            val isOne = ((word shr i) and 1) == 1
            if (isOne) {
                halfBits.add(false)
                halfBits.add(true)
            } else {
                halfBits.add(true)
                halfBits.add(false)
            }
        }
        // ConsumerIrManager expects pattern starting with MARK (true)
        var startIdx = 0
        while (startIdx < halfBits.size && !halfBits[startIdx]) startIdx++

        val pulses = ArrayList<Int>(32)
        if (startIdx < halfBits.size) {
            var currentState = halfBits[startIdx]
            var runLen = 889
            for (i in (startIdx + 1) until halfBits.size) {
                if (halfBits[i] == currentState) {
                    runLen += 889
                } else {
                    pulses.add(runLen)
                    currentState = halfBits[i]
                    runLen = 889
                }
            }
            pulses.add(runLen)
            // Ensure trailing space so pattern has even mark/space pairs
            if (pulses.size % 2 == 1) {
                pulses.add(89_000)
            } else {
                pulses[pulses.lastIndex] = pulses[pulses.lastIndex] + 89_000
            }
        }
        return IrSignal(frequencyHz = 36_000, patternUs = pulses.toIntArray())
    }

    private val samsungCodes = mapOf(
        "power" to 0x02,
        "volume_up" to 0x07,
        "volume_down" to 0x0B,
        "mute" to 0x0F,
        "channel_up" to 0x12,
        "channel_down" to 0x10,
        "input" to 0x01,
        "home" to 0x79,
        "back" to 0x58,
        "ok" to 0x68,
        "up" to 0x60,
        "down" to 0x61,
        "left" to 0x65,
        "right" to 0x62
    )

    private val lgCodes = mapOf(
        "power" to 0x08,
        "volume_up" to 0x02,
        "volume_down" to 0x03,
        "mute" to 0x09,
        "channel_up" to 0x00,
        "channel_down" to 0x01,
        "input" to 0x0B,
        "home" to 0x7C,
        "back" to 0x28,
        "ok" to 0x44,
        "up" to 0x40,
        "down" to 0x41,
        "left" to 0x07,
        "right" to 0x06
    )

    private val sonyCodes = mapOf(
        "power" to 0x15,
        "volume_up" to 0x12,
        "volume_down" to 0x13,
        "mute" to 0x14,
        "channel_up" to 0x10,
        "channel_down" to 0x11,
        "input" to 0x25,
        "home" to 0x60,
        "back" to 0x62,
        "ok" to 0x65,
        "up" to 0x74,
        "down" to 0x75,
        "left" to 0x34,
        "right" to 0x33
    )

    private val philipsCodes = mapOf(
        "power" to 0x0C,
        "volume_up" to 0x10,
        "volume_down" to 0x11,
        "mute" to 0x0D,
        "channel_up" to 0x20,
        "channel_down" to 0x21,
        "input" to 0x38,
        "home" to 0x54,
        "back" to 0x0A,
        "ok" to 0x57,
        "up" to 0x50,
        "down" to 0x51,
        "left" to 0x55,
        "right" to 0x56
    )

    private val hisenseCodes = mapOf(
        "power" to 0x0D,
        "volume_up" to 0x44,
        "volume_down" to 0x43,
        "mute" to 0x0E,
        "channel_up" to 0x4A,
        "channel_down" to 0x4B,
        "input" to 0x12,
        "home" to 0x14,
        "back" to 0x48,
        "ok" to 0x15,
        "up" to 0x16,
        "down" to 0x17,
        "left" to 0x19,
        "right" to 0x18
    )

    private val xiaomiCodes = mapOf(
        "power" to 0x01,
        "volume_up" to 0x02,
        "volume_down" to 0x03,
        "mute" to 0x0D,
        "home" to 0x04,
        "back" to 0x05,
        "ok" to 0x0B,
        "up" to 0x07,
        "down" to 0x08,
        "left" to 0x09,
        "right" to 0x0A
    )

    fun supportedBrands(): List<String> = listOf("samsung", "lg", "sony", "philips", "hisense", "xiaomi", "raw")

    fun resolvePreset(brand: String, command: String): IrSignal? {
        val b = brand.trim().lowercase()
        val c = command.trim().lowercase()
        return when (b) {
            "samsung" -> samsungCodes[c]?.let { encodeSamsung32(0x07, it) }
            "lg" -> lgCodes[c]?.let { encodeNec(0x04, it) }
            "sony" -> sonyCodes[c]?.let { encodeSony12(0x01, it) }
            "philips" -> philipsCodes[c]?.let { encodeRc5(0x00, it) }
            "hisense" -> hisenseCodes[c]?.let { encodeNec(0x00, it, invertedAddress = 0xBF) }
            "xiaomi", "mi" -> xiaomiCodes[c]?.let { encodeNec(0x00, it, invertedAddress = 0xBF) }
            else -> null
        }
    }

    fun parseRawPattern(patternCsv: String): IntArray? {
        val parts = patternCsv.split(",", " ", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 4) return null
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v <= 0 || v > 500_000) return null
            out[i] = v
        }
        return out
    }
}

class ControlIrDeviceTool(private val context: Context) : JarvisTool {
    override val spec = ToolSpec(
        name = "control_ir_device",
        description = "Transmits infrared (IR) remote control signals using the phone's built-in Xiaomi IR blaster to control TVs and home entertainment gear (Samsung, LG, Sony, Philips, Hisense, Xiaomi, or custom raw patterns).",
        params = listOf(
            ToolParam(
                name = "brand",
                type = "string",
                description = "TV/device brand: samsung, lg, sony, philips, hisense, xiaomi, or raw.",
                required = true,
                enumValues = IrProtocolEncoder.supportedBrands()
            ),
            ToolParam(
                name = "command",
                type = "string",
                description = "Remote button command: power, volume_up, volume_down, mute, channel_up, channel_down, input, home, back, ok, up, down, left, right (ignored when brand='raw').",
                enumValues = listOf(
                    "power", "volume_up", "volume_down", "mute",
                    "channel_up", "channel_down", "input", "home",
                    "back", "ok", "up", "down", "left", "right"
                )
            ),
            ToolParam(
                name = "repeat",
                type = "integer",
                description = "How many times to send the button press (1-8, default 1; useful for volume steps)."
            ),
            ToolParam(
                name = "frequency_hz",
                type = "integer",
                description = "Carrier frequency in Hz (default 38000), only used when brand='raw'."
            ),
            ToolParam(
                name = "pattern_us",
                type = "string",
                description = "Comma-separated alternating on/off durations in microseconds, only used when brand='raw'."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: return ToolExecutionOutput(false, "ConsumerIrManager is unavailable on this device.")
        if (!irManager.hasIrEmitter()) {
            return ToolExecutionOutput(false, "This device does not report a hardware IR blaster.")
        }

        val brand = args.optString("brand").trim().lowercase()
        val command = args.optString("command").trim().lowercase()
        val repeat = args.optInt("repeat", 1).coerceIn(1, 8)

        val signal = if (brand == "raw") {
            val freq = args.optInt("frequency_hz", 38_000).coerceIn(20_000, 60_000)
            val patternStr = args.optString("pattern_us").trim()
            val pattern = IrProtocolEncoder.parseRawPattern(patternStr)
                ?: return ToolExecutionOutput(
                    false,
                    "Invalid raw IR pattern_us. Provide at least 4 positive microsecond integers separated by commas."
                )
            IrProtocolEncoder.IrSignal(freq, pattern)
        } else {
            if (command.isBlank()) {
                return ToolExecutionOutput(false, "Missing 'command' for IR brand '$brand'.")
            }
            IrProtocolEncoder.resolvePreset(brand, command)
                ?: return ToolExecutionOutput(
                    false,
                    "Unsupported IR combination brand='$brand', command='$command'. Supported brands: ${IrProtocolEncoder.supportedBrands().joinToString(", ")}."
                )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                repeat(repeat) { idx ->
                    irManager.transmit(signal.frequencyHz, signal.patternUs)
                    if (idx < repeat - 1) delay(120L)
                }
                val label = if (brand == "raw") "raw ${signal.frequencyHz} Hz signal" else "$brand $command"
                val times = if (repeat > 1) " (${repeat}x)" else ""
                ToolExecutionOutput(
                    success = true,
                    message = "Transmitted IR command: $label$times.",
                    data = JSONObject()
                        .put("brand", brand)
                        .put("command", command)
                        .put("frequency_hz", signal.frequencyHz)
                        .put("pulses", signal.patternUs.size)
                        .put("repeat", repeat)
                )
            }.getOrElse { err ->
                ToolExecutionOutput(false, "IR transmission failed: ${err.message}")
            }
        }
    }
}
