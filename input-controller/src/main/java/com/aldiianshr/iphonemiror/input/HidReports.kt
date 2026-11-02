package com.aldiianshr.iphonemiror.input

/**
 * HID report descriptors and encoders for the tablet acting as a Bluetooth keyboard +
 * pointer + consumer-control device for an iPhone.
 *
 * Pointer modes:
 * - ABSOLUTE: Generic Desktop X/Y as uint16 in 0..32767 inside a Mouse collection. iOS 18+
 *   with AssistiveTouch places the pointer at that position of the screen — a click is a
 *   tap and a held button + motion is a swipe/drag. Layout follows the absolute build of
 *   jinwon-int/esp32-iphone-agent (MIT), verified there on an iPhone over BLE HID.
 * - RELATIVE: classic int8 dx/dy mouse (works on older iOS, needs AssistiveTouch too).
 *
 * The keyboard collection is declared first: iOS only treats the device as a hardware
 * keyboard (honouring Shift etc.) when it is (esp32-iphone-agent #17).
 */
object HidReports {
    const val ID_MOUSE: Int = 1
    const val ID_KEYBOARD: Int = 2
    const val ID_CONSUMER: Int = 3

    const val ABS_MAX = 32767

    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04

    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LGUI = 0x08 // ⌘ Command

    // Consumer page usages (16-bit array report).
    const val CONSUMER_HOME = 0x0040        // "Menu": mapped to Home by Apple's accessory guidelines
    const val CONSUMER_PLAY_PAUSE = 0x00CD
    const val CONSUMER_VOLUME_UP = 0x00E9
    const val CONSUMER_VOLUME_DOWN = 0x00EA
    const val CONSUMER_MUTE = 0x00E2
    const val CONSUMER_SEARCH = 0x0221

    private val KEYBOARD = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
        0xC0,
    )

    private val MOUSE_HEAD = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
    )

    private val MOUSE_ABS_XY = intArrayOf(
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x00, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    )

    private val MOUSE_REL_XY = intArrayOf(
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
    )

    private val MOUSE_TAIL = intArrayOf(
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
    )

    private val CONSUMER = intArrayOf(
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    fun descriptor(absolute: Boolean): ByteArray =
        (KEYBOARD + MOUSE_HEAD + (if (absolute) MOUSE_ABS_XY else MOUSE_REL_XY) + MOUSE_TAIL + CONSUMER)
            .map { it.toByte() }.toByteArray()

    /** Relative mouse body: buttons, dx, dy, wheel, pan. */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x1F).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte(),
        pan.coerceIn(-127, 127).toByte(),
    )

    /** Absolute pointer body: buttons, x (LE uint16), y (LE uint16), wheel, pan. */
    fun absMouse(buttons: Int, x: Int, y: Int, wheel: Int = 0, pan: Int = 0): ByteArray {
        val cx = x.coerceIn(0, ABS_MAX)
        val cy = y.coerceIn(0, ABS_MAX)
        return byteArrayOf(
            (buttons and 0x1F).toByte(),
            (cx and 0xFF).toByte(), (cx shr 8).toByte(),
            (cy and 0xFF).toByte(), (cy shr 8).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
            pan.coerceIn(-127, 127).toByte(),
        )
    }

    /** Keyboard body: modifiers, reserved, up to 6 key usages. */
    fun keyboard(modifiers: Int, keys: List<Int>): ByteArray {
        val out = ByteArray(8)
        out[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> out[2 + i] = k.toByte() }
        return out
    }

    /** Consumer body: one 16-bit usage (0 = released). */
    fun consumer(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0x03).toByte())

    /** Splits a large relative movement into HID-sized steps (|d| ≤ maxStep). */
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
