package app.recode.core

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * Values are represented as: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 * Kept in the pure core so it can be unit-tested on the JVM without Android stubs.
 */
object Json {

    class ParseException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing data at ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Enum<*> -> writeString(sb, v.name)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) sb.append("null")
        else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong().toString())
        else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Expected $word at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw ParseException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw ParseException("Expected ':' at $pos")
                pos++
                map[key] = readValue()
                skipWs()
                if (atEnd()) throw ParseException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                if (atEnd()) throw ParseException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val num = s.substring(start, pos)
            return if (num.any { it == '.' || it == 'e' || it == 'E' }) {
                num.toDoubleOrNull() ?: throw ParseException("Bad number '$num'")
            } else {
                num.toLongOrNull() ?: num.toDoubleOrNull() ?: throw ParseException("Bad number '$num'")
            }
        }
    }
}

// ---- Typed accessors for parsed JSON trees ----

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

fun Any?.arr(): List<Any?> = (this as? List<*>)?.toList() ?: emptyList()

fun Map<String, Any?>.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v
    else -> v.toString()
}

fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
    else -> null
}

fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()

fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is Long -> v != 0L
    is String -> v.equals("true", true) || v == "1"
    else -> null
}
