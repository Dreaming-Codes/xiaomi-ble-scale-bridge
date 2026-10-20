package dev.lorenzo.s200

enum class ScaleModel(val model: String, val productId: Int, val displayName: String) {
    MS106("yunmai.scales.ms106", 0x45C9, "Mijia Smart Scale S200, white (China)"),
    MS111("xiaomi.scales.ms111", 0x4C04, "Xiaomi Smart Scale S200, white"),
    MS112("xiaomi.scales.ms112", 0x4D36, "Mijia Smart Scale S200, blue (China)"),
    MS113("xiaomi.scales.ms113", 0x4DCB, "Xiaomi Smart Scale S200, dark gray"),
    MS114("xiaomi.scales.ms114", 0x4DCC, "Xiaomi Smart Scale S200 (Japan and Korea)");

    companion object {
        fun ofModel(model: String): ScaleModel? = entries.firstOrNull { it.model == model }
        fun ofProductId(pid: Int): ScaleModel? = entries.firstOrNull { it.productId == pid }
    }
}
