package com.aldiianshr.iphonemiror.domain.model

/**
 * Maps Apple product identifiers (as sent by the device itself, e.g. "iPhone16,2") to
 * marketing names. Unknown identifiers are shown as-is.
 */
object AppleModels {
    private val names = mapOf(
        "iPhone12,1" to "iPhone 11",
        "iPhone12,3" to "iPhone 11 Pro",
        "iPhone12,5" to "iPhone 11 Pro Max",
        "iPhone12,8" to "iPhone SE (2nd generation)",
        "iPhone13,1" to "iPhone 12 mini",
        "iPhone13,2" to "iPhone 12",
        "iPhone13,3" to "iPhone 12 Pro",
        "iPhone13,4" to "iPhone 12 Pro Max",
        "iPhone14,2" to "iPhone 13 Pro",
        "iPhone14,3" to "iPhone 13 Pro Max",
        "iPhone14,4" to "iPhone 13 mini",
        "iPhone14,5" to "iPhone 13",
        "iPhone14,6" to "iPhone SE (3rd generation)",
        "iPhone14,7" to "iPhone 14",
        "iPhone14,8" to "iPhone 14 Plus",
        "iPhone15,2" to "iPhone 14 Pro",
        "iPhone15,3" to "iPhone 14 Pro Max",
        "iPhone15,4" to "iPhone 15",
        "iPhone15,5" to "iPhone 15 Plus",
        "iPhone16,1" to "iPhone 15 Pro",
        "iPhone16,2" to "iPhone 15 Pro Max",
        "iPhone17,1" to "iPhone 16 Pro",
        "iPhone17,2" to "iPhone 16 Pro Max",
        "iPhone17,3" to "iPhone 16",
        "iPhone17,4" to "iPhone 16 Plus",
        "iPhone17,5" to "iPhone 16e",
        "iPhone18,1" to "iPhone 17 Pro",
        "iPhone18,2" to "iPhone 17 Pro Max",
        "iPhone18,3" to "iPhone 17",
        "iPhone18,4" to "iPhone Air",
    )

    fun marketingName(identifier: String): String? = names[identifier]

    fun isIPhone(identifier: String?): Boolean = identifier?.startsWith("iPhone") == true
    fun isIPad(identifier: String?): Boolean = identifier?.startsWith("iPad") == true
}
