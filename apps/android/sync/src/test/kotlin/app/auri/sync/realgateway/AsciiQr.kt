package app.auri.sync.realgateway

import app.auri.sync.pairing.QrDecoder

/**
 * 把网关命令行打印的终端二维码（`auri_gateway pair`，qrcode 库的 print_ascii(invert=True)）还原成一张灰度图，
 * 交给手机端真正用的 [QrDecoder]。这样“二维码配对”测的是真东西：网关画出来的、人会拿手机去扫的那张码。
 *
 * 终端里一个字符是上下两个模块，深色模块画成“空”，浅色画成“亮块”（给深色终端看的反相画法）：
 * `█` 上下都浅；`▀` 上浅下深；`▄` 上深下浅；空格或不换行空格 上下都深。
 */
object AsciiQr {
    private const val SCALE = 4
    private const val QUIET_MODULES = 4
    private val BLOCKS = setOf('█', '▀', '▄', ' ', '\u00a0')

    /** 终端输出里的二维码文字；找不到或读不出来返回 null。 */
    fun decode(terminalOutput: String): String? {
        val rows = terminalOutput.lines().filter { line -> line.length >= 21 && line.all { it in BLOCKS } }
        if (rows.isEmpty()) return null
        val columns = rows.maxOf { it.length }
        val moduleRows = rows.size * 2
        val width = (columns + 2 * QUIET_MODULES) * SCALE
        val height = (moduleRows + 2 * QUIET_MODULES) * SCALE
        val pixels = ByteArray(width * height) { 255.toByte() } // 白底，四周留静区
        fun paintDark(moduleX: Int, moduleY: Int) {
            for (dy in 0 until SCALE) for (dx in 0 until SCALE) {
                pixels[((moduleY + QUIET_MODULES) * SCALE + dy) * width + (moduleX + QUIET_MODULES) * SCALE + dx] = 0
            }
        }
        rows.forEachIndexed { rowIndex, line ->
            for (column in 0 until columns) {
                val char = line.getOrElse(column) { '█' }
                val topDark = char == '▄' || char == ' ' || char == '\u00a0'
                val bottomDark = char == '▀' || char == ' ' || char == '\u00a0'
                if (topDark) paintDark(column, rowIndex * 2)
                if (bottomDark) paintDark(column, rowIndex * 2 + 1)
            }
        }
        return QrDecoder.decode(pixels, width, height)
    }
}
