package org.example.videoplayerkmp.data

/**
 * Позиции треков в плейлисте — строки, которые сравниваются как обычные строки.
 *
 * Между любыми двумя позициями всегда найдётся третья, поэтому перенос или вставка трека
 * меняет запись только этого трека: два устройства могут менять порядок одновременно,
 * не перенумеровывая весь плейлист. Цифры — 0-9a-z, позиция никогда не кончается на «0»
 * (иначе между «a» и «a0» нечего было бы вставить).
 */
internal object Positions {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val BASE = DIGITS.length

    /** Позиция строго между [before] и [after]; null — край списка. Требует before < after. */
    fun between(before: String?, after: String?): String {
        require(before == null || after == null || before < after) { "Позиции не по порядку: $before, $after" }
        return when {
            after == null -> next(before.orEmpty())
            else -> midpoint(before.orEmpty(), after)
        }
    }

    /** [count] позиций по порядку — для нового плейлиста. Все одной длины и короткие. */
    fun sequence(count: Int): List<String> {
        if (count == 0) return emptyList()
        var width = 1
        var capacity = BASE
        while (capacity <= count) {
            width++
            capacity *= BASE
        }
        // Последняя «i» — чтобы позиция не кончалась нулём
        return (1..count).map { it.toString(BASE).padStart(width, '0') + "i" }
    }

    /**
     * Следующая позиция за [a]; растёт на одну цифру раз в 35 добавлений в конец.
     * Первая позиция пустого списка — середина («i»), чтобы осталось место перед ней.
     */
    private fun next(a: String, deeper: Boolean = false): String {
        if (a.isEmpty()) return (if (deeper) DIGITS[1] else DIGITS[BASE / 2]).toString()
        val first = DIGITS.indexOf(a[0])
        return if (first < BASE - 1) DIGITS[first + 1].toString() else a[0] + next(a.substring(1), deeper = true)
    }

    private fun midpoint(a: String, b: String): String {
        // Общее начало оставляем как есть; недостающие цифры у a — нули
        var n = 0
        while (n < b.length && (a.getOrNull(n) ?: '0') == b[n]) n++
        if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.substring(n))

        val da = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val db = DIGITS.indexOf(b[0])
        if (db - da > 1) return DIGITS[(da + db) / 2].toString()
        // Соседние цифры: «b[0]» уже больше a, если за ней в b ещё что-то есть
        if (b.length > 1) return b.substring(0, 1)
        // Иначе берём цифру a и продолжаем вверх без ограничения: результат всё равно меньше b
        return DIGITS[da] + next(a.drop(1))
    }
}
