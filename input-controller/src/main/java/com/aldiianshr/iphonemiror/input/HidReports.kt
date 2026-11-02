package com.aldiianshr.iphonemiror.input

/**
 * USB HID report descriptor and report encoders for a combined relative mouse,
 * keyboard and consumer-control device. iOS accepts these over Bluetooth HID:
 * keyboards natively, pointing devices once AssistiveTouch is enabled
 * (Settings → Accessibility → Touch → AssistiveTouch).
 */
object HidReports {
    const val ID_MOUSE: Int = 1
    const val ID_KEYBOARD: Int = 2
    const val ID_CONSUMER: Int = 3

    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04

    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LGUI = 0x08 // ⌘ Command

    const val CONSUMER_PLAY_PAUSE = 0x0001
    const val CONSUMER_VOLUME_UP = 0x0002
    const val CONSUMER_VOLUME_DOWN = 0x0004
    const val CONSUMER_MUTE = 0x0008

    val DESCRIPTOR: ByteArray = intArrayOf(
        // Mouse
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // Keyboard
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Consumer control (play/pause, volume)
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04,
        0x09, 0xCD, 0x09, 0xE9, 0x09, 0xEA, 0x09, 0xE2, 0x81, 0x02,
        0x95, 0x04, 0x81, 0x03,
        0xC0,
    ).map { it.toByte() }.toByteArray()

    /** Mouse report body (without report id): buttons, dx, dy, wheel, horizontal pan. */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte(),
        pan.coerceIn(-127, 127).toByte(),
    )

    /** Keyboard report body: modifiers, reserved, up to 6 key usages. */
    fun keyboard(modifiers: Int, keys: List<Int>): ByteArray {
        val out = ByteArray(8)
        out[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> out[2 + i] = k.toByte() }
        return out
    }

    fun consumer(bits: Int): ByteArray = byteArrayOf((bits and 0x0F).toByte())

    /** Splits a large relative movement into HID-sized steps (|d| ≤ 127). */
    fun splitMovement(dx: Int, dy: Int, maxStep: Int = 127): List<Pair<Int, Int>> {
        val steps = maxOf(1, (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) + maxStep - 1) / maxStep)
        val out = ArrayList<Pair<Int, Int>>(steps)
        var sentX = 0
        var sentY = 0
        for (i in 1..steps) {
            val tx = dx * i / steps
            val ty = dy * i / steps
            out += (tx - sentX) to (ty - sentY)
            sentX = tx; sentY = ty
        }
        return out.filter { it.first != 0 || it.second != 0 }
    }
}

/** US keyboard layout → HID usage IDs (Keyboard/Keypad page 0x07). */
object KeyMap {
    data class Stroke(val usage: Int, val modifiers: Int = 0)

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val KEY_H = 0x0B

    private const val SHIFT = HidReports.MOD_LSHIFT

    fun forChar(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0x04 + (c - 'a'))
        in 'A'..'Z' -> Stroke(0x04 + (c - 'A'), SHIFT)
        in '1'..'9' -> Stroke(0x1E + (c - '1'))
        '0' -> Stroke(0x27)
        '\n' -> Stroke(ENTER)
        '\t' -> Stroke(TAB)
        ' ' -> Stroke(SPACE)
        '!' -> Stroke(0x1E, SHIFT); '@' -> Stroke(0x1F, SHIFT); '#' -> Stroke(0x20, SHIFT)
        '$' -> Stroke(0x21, SHIFT); '%' -> Stroke(0x22, SHIFT); '^' -> Stroke(0x23, SHIFT)
        '&' -> Stroke(0x24, SHIFT); '*' -> Stroke(0x25, SHIFT); '(' -> Stroke(0x26, SHIFT)
        ')' -> Stroke(0x27, SHIFT)
        '-' -> Stroke(0x2D); '_' -> Stroke(0x2D, SHIFT)
        '=' -> Stroke(0x2E); '+' -> Stroke(0x2E, SHIFT)
        '[' -> Stroke(0x2F); '{' -> Stroke(0x2F, SHIFT)
        ']' -> Stroke(0x30); '}' -> Stroke(0x30, SHIFT)
        '\\' -> Stroke(0x31); '|' -> Stroke(0x31, SHIFT)
        ';' -> Stroke(0x33); ':' -> Stroke(0x33, SHIFT)
        '\'' -> Stroke(0x34); '"' -> Stroke(0x34, SHIFT)
        '`' -> Stroke(0x35); '~' -> Stroke(0x35, SHIFT)
        ',' -> Stroke(0x36); '<' -> Stroke(0x36, SHIFT)
        '.' -> Stroke(0x37); '>' -> Stroke(0x37, SHIFT)
        '/' -> Stroke(0x38); '?' -> Stroke(0x38, SHIFT)
        else -> null
    }
}
