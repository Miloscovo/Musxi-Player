package tech.musxi.core.localaudio

/** Natural decimal ordering without parsing an unbounded track number into an Int/Long. */
object LocalAudioNames : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var x = 0
        var y = 0
        while (x < a.length && y < b.length) {
            if (a[x] in '0'..'9' && b[y] in '0'..'9') {
                val startX = x
                val startY = y
                while (x < a.length && a[x] in '0'..'9') x++
                while (y < b.length && b[y] in '0'..'9') y++
                var significantX = startX
                var significantY = startY
                while (significantX < x - 1 && a[significantX] == '0') significantX++
                while (significantY < y - 1 && b[significantY] == '0') significantY++
                val lengthOrder = (x - significantX).compareTo(y - significantY)
                if (lengthOrder != 0) return lengthOrder
                for (offset in 0 until x - significantX) {
                    val digitOrder = a[significantX + offset].compareTo(b[significantY + offset])
                    if (digitOrder != 0) return digitOrder
                }
            } else {
                val letterOrder = a[x].lowercaseChar().compareTo(b[y].lowercaseChar())
                if (letterOrder != 0) return letterOrder
                x++; y++
            }
        }
        return (a.length - x).compareTo(b.length - y).takeIf { it != 0 } ?: a.compareTo(b)
    }

    fun clean(value: String?): String? = value?.filterNot { it.isISOControl() }
        ?.trim()?.take(LocalAudioLimits.MAX_NAME)?.takeIf { it.isNotBlank() }
}
