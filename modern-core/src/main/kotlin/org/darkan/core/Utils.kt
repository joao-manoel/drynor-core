package org.darkan.core

import org.darkan.core.cache.DefinitionTables
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.secureRandom
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

object Utils {
    @JvmField
    val ROTATION_DIR_X = intArrayOf(-1, 0, 1, 0)
    @JvmField
    val ROTATION_DIR_Y = intArrayOf(0, 1, 0, -1)
    @JvmField
    val FORMAT = DecimalFormat("###,###.##")

    private val ANGLE_DIRECTION_DELTA = arrayOf(
        byteArrayOf(0, -1), byteArrayOf(-1, -1), byteArrayOf(-1, 0),
        byteArrayOf(-1, 1), byteArrayOf(0, 1), byteArrayOf(1, 1),
        byteArrayOf(1, 0), byteArrayOf(1, -1)
    )

    @JvmStatic
    fun random(maxValue: Int): Int = if (maxValue <= 0) 0 else secureRandom.nextInt(maxValue)

    @JvmStatic
    fun random(min: Int, max: Int): Int {
        val diff = abs(max - min)
        return min + if (diff == 0) 0 else random(diff)
    }

    @JvmStatic
    fun randomInclusive(min: Int, max: Int): Int = random(min, max + 1)

    @JvmStatic
    fun random(maxValue: Double): Double = random(0.0, maxValue)

    @JvmStatic
    fun random(min: Double, max: Double): Double = min + (max - min) * secureRandom.nextDouble()

    @JvmStatic
    fun randomD(): Double = secureRandom.nextDouble()

    @JvmStatic
    fun getRandomInclusive(maxValue: Int): Int = (Math.random() * (maxValue + 1)).toInt()

    @JvmStatic
    fun next(max: Int, min: Int): Int = min + (Math.random() * ((max - min) + 1)).toInt()

    @JvmStatic
    fun randomLong(n: Long): Long {
        var bits: Long
        var value: Long
        do {
            bits = (secureRandom.nextLong() shl 1) ushr 1
            value = bits % n
        } while (bits - value + (n - 1) < 0L)
        return value
    }

    @JvmStatic
    fun range(min: Int, max: Int): IntArray = IntArray(max - min + 1) { min + it }

    @JvmStatic
    fun range(min: Int, max: Int, step: Int): IntArray {
        val size = (max - min) / step + 1
        return IntArray(size) { min + it * step }
    }

    @JvmStatic
    fun clampD(value: Double, min: Double, max: Double): Double = max(min, min(max, value))

    @JvmStatic
    fun clampI(value: Int, min: Int, max: Int): Int = max(min, min(max, value))

    @JvmStatic
    fun satAdd(a: Long, b: Long): Long {
        val sum = a + b
        if (((a xor sum) and (b xor sum)) < 0)
            return if (b > 0) Long.MAX_VALUE else Long.MIN_VALUE
        return sum
    }

    @JvmStatic
    fun satMul(a: Long, b: Long): Long {
        if (a == 0L || b == 0L)
            return 0L
        val product = a * b
        if (product / a != b || (a == -1L && b == Long.MIN_VALUE))
            return if ((a xor b) < 0) Long.MIN_VALUE else Long.MAX_VALUE
        return product
    }

    @JvmStatic
    fun satAdd(a: Int, b: Int): Int = satInt(a.toLong() + b.toLong())

    @JvmStatic
    fun satInt(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    @JvmStatic
    fun satInt(value: Double): Int = if (value.isNaN()) 0 else value.toInt()

    @JvmStatic
    fun diff(a: Int, b: Int): Int = abs(a - b)

    @JvmStatic
    fun round(value: Double, places: Int): Double =
        BigDecimal(value).setScale(places, RoundingMode.HALF_UP).toDouble()

    @JvmStatic
    fun isWithin(i: Int, min: Int, max: Int): Boolean = i > min && i < max

    @JvmStatic
    fun isWithinInclusive(i: Int, min: Int, max: Int): Boolean = i in min..max

    @JvmStatic
    fun skillSuccess(level: Int, rate1: Int, rate99: Int): Boolean = skillSuccess(level, 1.0, rate1, rate99)

    @JvmStatic
    fun skillSuccess(level: Int, percModifier: Double, rate1: Int, rate99: Int): Boolean =
        skillSuccess(level, percModifier, rate1, rate99, 256)

    @JvmStatic
    fun skillSuccess(level: Int, percModifier: Double, rate1: Int, rate99: Int, maxSuccess: Int): Boolean {
        val low = percModifier * rate1
        val high = percModifier * rate99
        val chance = floor(low * (99 - level) / 98.0).toInt() + floor(high * (level - 1) / 98.0).toInt() + 1
        return random(maxSuccess) < chance
    }

    @JvmStatic
    fun gcm(a: Long, b: Long): Long = if (b == 0L) a else gcm(b, a % b)

    @JvmStatic
    fun greatestCommonDenominator(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDenominator(b, a % b)

    @JvmStatic
    fun leastCommonMultiple(a: Int, b: Int): Int = a / greatestCommonDenominator(a, b) * b

    @JvmStatic
    fun getDistance(t1: Tile, t2: Tile): Double = getDistance(t1.x, t1.y, t2.x, t2.y)

    @JvmStatic
    fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    @JvmStatic
    fun getDistanceI(t1: Tile, t2: Tile): Int = getDistanceI(t1.x, t1.y, t2.x, t2.y)

    @JvmStatic
    fun getDistanceI(x1: Int, y1: Int, x2: Int, y2: Int): Int = getDistance(x1, y1, x2, y2).toInt()

    @JvmStatic
    fun inCircle(location: Tile, center: Tile, radius: Int): Boolean {
        return getDistance(center, location) < radius
    }

    @JvmStatic
    fun getAngleTo(from: Tile, to: Tile): Int = getAngleTo(to.x - from.x, to.y - from.y)

    @JvmStatic
    fun getAngleTo(xOffset: Int, yOffset: Int): Int {
        return (atan2(-xOffset.toDouble(), -yOffset.toDouble()) * 2607.5945876176133).toInt() and 0x3fff
    }

    @JvmStatic
    fun getDirection(angle: Int): ByteArray = ANGLE_DIRECTION_DELTA[angle shr 11]

    @JvmStatic
    fun getPlayerWalkingDirection(dx: Int, dy: Int): Int = when {
        dx == -1 && dy == -1 -> 0
        dx == 0 && dy == -1 -> 1
        dx == 1 && dy == -1 -> 2
        dx == -1 && dy == 0 -> 3
        dx == 1 && dy == 0 -> 4
        dx == -1 && dy == 1 -> 5
        dx == 0 && dy == 1 -> 6
        dx == 1 && dy == 1 -> 7
        else -> -1
    }

    @JvmStatic
    fun getPlayerRunningDirection(dx: Int, dy: Int): Int = when {
        dx == -2 && dy == -2 -> 0
        dx == -1 && dy == -2 -> 1
        dx == 0 && dy == -2 -> 2
        dx == 1 && dy == -2 -> 3
        dx == 2 && dy == -2 -> 4
        dx == -2 && dy == -1 -> 5
        dx == 2 && dy == -1 -> 6
        dx == -2 && dy == 0 -> 7
        dx == 2 && dy == 0 -> 8
        dx == -2 && dy == 1 -> 9
        dx == 2 && dy == 1 -> 10
        dx == -2 && dy == 2 -> 11
        dx == -1 && dy == 2 -> 12
        dx == 0 && dy == 2 -> 13
        dx == 1 && dy == 2 -> 14
        dx == 2 && dy == 2 -> 15
        else -> -1
    }

    @JvmStatic
    fun getBackFace(dir: Int): IntArray {
        val ang = dir / 2607.5945876176133
        return intArrayOf(sin(ang).roundToInt(), cos(ang).roundToInt())
    }

    @JvmStatic
    fun getFrontFace(dir: Int): IntArray {
        val front = getBackFace(dir)
        return intArrayOf(front[0] * -1, front[1] * -1)
    }

    @JvmStatic
    fun formatPlayerNameForDisplay(name: String): String = name.formatPlayerNameForDisplay()

    @JvmStatic
    fun formatPlayerNameForProtocol(name: String): String = name.formatPlayerNameForProtocol()

    @JvmStatic
    fun fixChatMessage(message: String): String = message.fixChatMessage()

    @JvmStatic
    fun addArticle(name: String): String {
        val lower = name.lowercase()
        return if (lower.startsWith("a") || lower.startsWith("e") || lower.startsWith("i") || lower.startsWith("o") || lower.startsWith("u"))
            "an $name" else "a $name"
    }

    @JvmStatic
    fun kmify(num: Int): String = when {
        num >= 10_000_000 -> "${num / 1_000_000}M"
        num >= 100_000 -> "${num / 1_000}K"
        else -> num.formatNumber()
    }

    @JvmStatic
    fun quote(str: String): String = "'$str'"

    @JvmStatic
    fun quote(c: Char): String = "'$c'"

    @JvmStatic
    fun concat(args: Array<String>): String = args.joinToString(" ")

    @JvmStatic
    fun concat(args: Array<String>, start: Int): String = args.drop(start).joinToString(" ")

    @JvmStatic
    fun formatNumber(number: Int): String = number.formatNumber()

    @JvmStatic
    fun getFormattedNumber(amount: Int): String = NumberFormat.getNumberInstance(Locale.US).format(amount.toLong())

    @JvmStatic
    fun getFormattedNumber(amount: Double, separator: Char): String {
        val format = DecimalFormat("#,###")
        val symbols = format.decimalFormatSymbols
        symbols.groupingSeparator = separator
        format.decimalFormatSymbols = symbols
        return format.format(amount)
    }

    @JvmStatic
    fun formatTypicalInteger(integer: Int): String = NumberFormat.getIntegerInstance().format(integer.toLong())

    @JvmStatic
    fun formatLong(value: Long): String = NumberFormat.getNumberInstance(Locale.US).format(value)

    @JvmStatic
    fun formatDouble(d: Double): String = FORMAT.format(d)

    @JvmStatic
    fun formatTime(time: Long): String {
        val sec = (time / 1000) % 60
        val min = (time / 60000) % 60
        val hr = time / 3600000
        return String.format("%02d:%02d:%02d", hr, min, sec)
    }

    @JvmStatic
    fun ticksToTime(ticks: Double): String = ticks.ticksToTimeString()

    @JvmStatic
    fun getFormatedDate(): String = SimpleDateFormat("[MM/d/yyyy]").format(Date())

    @JvmStatic
    fun getDateString(): String = SimpleDateFormat("yyyy-MM-dd").format(Date())

    @JvmStatic
    fun invalidAccountName(name: String): Boolean = !name.isValidAccountName()

    @JvmStatic
    fun containsInvalidCharacter(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'

    @JvmStatic
    fun containsInvalidCharacter(name: String): Boolean = name.any { containsInvalidCharacter(it) }

    @JvmStatic
    fun containsBadCharacter(c: Char): Boolean = !c.isLetterOrDigit() && c != ' ' && c != '-' && c != '_'

    @JvmStatic
    fun containsBadCharacter(name: String): Boolean = name.any { containsBadCharacter(it) }

    @JvmStatic
    fun validEmail(emailStr: String): Boolean = emailStr.isValidEmail()

    @JvmStatic
    fun stringToLong(s: String): Long {
        var l = 0L
        for (i in 0 until minOf(s.length, 12)) {
            val c = s[i]
            l *= 37L
            l += when {
                c in 'A'..'Z' -> (c - 'A' + 1).toLong()
                c in 'a'..'z' -> (c - 'a' + 1).toLong()
                c in '0'..'9' -> (c - '0' + 27).toLong()
                else -> 0L
            }
        }
        while (l % 37L == 0L && l != 0L) l /= 37L
        return l
    }

    @JvmStatic
    fun longToString(l: Long): String {
        if (l <= 0L || l >= 0x5B5B57F8A98A5DD1L) return "invalid_name"
        var remaining = l
        var len = 0
        var temp = remaining
        while (temp != 0L) {
            temp /= 37L
            len++
        }
        val chars = CharArray(len)
        for (i in len - 1 downTo 0) {
            val digit = (remaining % 37L).toInt()
            remaining /= 37L
            chars[i] = when {
                digit == 0 -> '_'
                digit < 27 -> ('a' + digit - 1)
                else -> ('0' + digit - 27)
            }
        }
        return String(chars)
    }

    @JvmStatic
    fun getNameHash(name: String): Int {
        var hash = 0
        for (c in name.formatPlayerNameForProtocol()) {
            hash = (hash shl 5) - hash + c.code
        }
        return hash
    }

    @JvmStatic
    fun interfaceIdFromHash(hash: Int): Int = hash shr 16

    @JvmStatic
    fun componentIdFromHash(hash: Int): Int = hash and 0xFFFF

    @JvmStatic
    fun toInterfaceHash(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) or componentId

    @JvmStatic
    fun calcProjectileInAirTime(start: Tile, end: Tile, presetInAirClientCycles: Int): Int {
        val dist = getDistance(start, end)
        return presetInAirClientCycles + (dist * 5).toInt()
    }

    @JvmStatic
    fun projInAirToTicks(inAirClientCycles: Int): Int = projInAirToTicks(0, inAirClientCycles)

    @JvmStatic
    fun projInAirToTicks(startDelayClientCycles: Int, inAirClientCycles: Int): Int =
        ceil((startDelayClientCycles + inAirClientCycles) / 30.0).toInt()

    @JvmStatic
    fun cryptRSA(data: ByteArray, exponent: BigInteger, modulus: BigInteger): ByteArray =
        BigInteger(data).modPow(exponent, modulus).toByteArray()

    @JvmStatic
    fun encryptUsingMD5(buffer: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(buffer)

    @JvmStatic
    fun getRSColor(red: Int, green: Int, blue: Int): Int {
        val r = red / 256.0
        val g = green / 256.0
        val b = blue / 256.0
        var mn = minOf(r, g, b)
        var mx = maxOf(r, g, b)
        var h = 0.0
        var s: Double
        val l = (mn + mx) / 2.0
        if (mn == mx) {
            h = 0.0
            s = 0.0
        } else {
            val delta = mx - mn
            s = if (l < 0.5) delta / (mx + mn) else delta / (2.0 - mx - mn)
            if (r == mx) h = (g - b) / delta
            else if (g == mx) h = (b - r) / delta + 2.0
            else if (b == mx) h = (r - g) / delta + 4.0
        }
        h /= 6.0
        val hi = (h * 256.0).toInt()
        val si = (s * 256.0).toInt()
        val li = (l * 256.0).toInt()
        if (si < 0) return li
        val hc = if (hi > 255) 255 else hi
        val sc = if (si > 255) 255 else si
        val lc = if (li > 255) 255 else li
        return (hc shl 10) + (sc shl 7) + lc
    }

    @JvmStatic
    fun djb2(str: String): Int {
        var hash = 0
        for (c in str) hash = c.code + ((hash shl 5) - hash)
        return hash
    }

    @JvmStatic
    fun getHashMapSize(size: Int): Int {
        var n = size - 1
        n = n or (n ushr 1)
        n = n or (n ushr 2)
        n = n or (n ushr 4)
        n = n or (n ushr 8)
        n = n or (n ushr 16)
        return n + 1
    }

    @JvmStatic
    fun getTodayDate(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return ((cal.get(Calendar.YEAR) - 2000) shl 9) or (cal.get(Calendar.MONTH) shl 5) or cal.get(Calendar.DAY_OF_MONTH)
    }

    @JvmStatic
    fun findClosestIdx(arr: IntArray, target: Int): Int {
        var closest = 0
        for (i in arr.indices) {
            if (abs(arr[i] - target) < abs(arr[closest] - target)) closest = i
        }
        return closest
    }

    @JvmStatic
    fun shuffleIntArray(array: IntArray): IntArray {
        val copy = array.copyOf()
        for (i in copy.indices.reversed()) {
            val j = secureRandom.nextInt(i + 1)
            val temp = copy[i]
            copy[i] = copy[j]
            copy[j] = temp
        }
        return copy
    }

    @JvmStatic
    fun randSum(numVars: Int, total: Int): IntArray {
        val result = IntArray(numVars)
        for (i in 0 until total) result[secureRandom.nextInt(numVars)]++
        return result
    }

    @JvmStatic
    fun removeInvalidChars(s: String): String = s.filter { it.isLetterOrDigit() || it == '_' || it == ' ' }

    @JvmStatic
    fun strikeThroughIf(string: String, condition: () -> Boolean): String =
        if (condition()) "<str>$string</str>" else string

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun cloneMap(from: Map<String, Any>): Map<String, Any> =
        from.entries.associate { (k, v) ->
            k to if (v is Map<*, *>) cloneMap(v as Map<String, Any>) else v
        }

    @JvmField
    val CP_1252_CHARACTERS = charArrayOf(
        '\u20ac', '\u0000', '\u201a', '\u0192', '\u201e', '\u2026', '\u2020', '\u2021',
        '\u02c6', '\u2030', '\u0160', '\u2039', '\u0152', '\u0000', '\u017d', '\u0000',
        '\u0000', '\u2018', '\u2019', '\u201c', '\u201d', '\u2022', '\u2013', '\u2014',
        '\u02dc', '\u2122', '\u0161', '\u203a', '\u0153', '\u0000', '\u017e', '\u0178'
    )

    @JvmStatic
    fun cp1252ToChar(i: Byte): Char {
        var cp = (i.toInt() and 0xff)
        if (cp == 0) throw IllegalArgumentException("Non cp1252 character 0x${Integer.toString(cp, 16)} provided")
        if (cp in 128..159) {
            var c = CP_1252_CHARACTERS[cp - 128]
            if (c.code == 0) c = '?'
            cp = c.code
        }
        return cp.toChar()
    }

    @JvmStatic
    fun charToCp1252(c: Char): Byte {
        if (c.code > 0 && c.code < 128 || c.code in 160..255) return c.code.toByte()
        for (i in CP_1252_CHARACTERS.indices) {
            if (CP_1252_CHARACTERS[i] == c) return (i + 128).toByte()
        }
        return '?'.code.toByte()
    }

    @JvmStatic fun getItemDefinitionsSize(): Int = Cache.items.size

    @JvmStatic fun getNPCDefinitionsSize(): Int = Cache.npcs.size
    @JvmStatic fun isQCValid(id: Int): Boolean = id >= 0 && Cache.get().files(24, 1).contains(id)

    @JvmStatic fun getObjectDefinitionsSize(): Int = Cache.objects.size

    @JvmStatic fun getSpotAnimDefinitionsSize(): Int = Cache.spotAnims.size

    @JvmStatic fun getAnimationDefinitionsSize(): Int = Cache.animations.size

    @JvmStatic fun getBASAnimDefSize(): Int = Cache.bas.size

    @JvmStatic fun getConfigDefinitionsSize(): Int {
        val cache = Cache.get()
        val lastArchive = cache.lastArchiveId(Index.VAR_BIT)
        if (lastArchive < 0) return 0
        return lastArchive * 256 + cache.fileCount(Index.VAR_BIT, lastArchive)
    }

    @JvmStatic fun getVarbitDefinitionsSize(): Int = Cache.varbits.size

    @JvmStatic fun getInterfaceDefinitionsSize(): Int =
        Cache.get().lastArchiveId(Index.INTERFACES) + 1

    @JvmStatic fun getInterfaceDefinitionsComponentsSize(interfaceId: Int): Int =
        Cache.get().lastFileId(Index.INTERFACES, interfaceId) + 1

    private val itemIdExists: BooleanArray by lazy {
        DefinitionTables.items?.let { table -> BooleanArray(table.size) { table[it].name != BLANK_ITEM_NAME } } ?: cacheItemIds()
    }

    private const val BLANK_ITEM_NAME = "null"

    private fun cacheItemIds(): BooleanArray {
        val size = getItemDefinitionsSize()
        val exists = BooleanArray(size)
        val cache = Cache.get()
        var archive = 0
        while ((archive shl 8) < size) {
            for (file in cache.files(Index.ITEMS, archive)) {
                val id = (archive shl 8) or file
                if (id < size) exists[id] = true
            }
            archive++
        }
        return exists
    }

    @JvmStatic fun itemExists(id: Int): Boolean = id >= 0 && id < itemIdExists.size && itemIdExists[id]

    @JvmStatic
    fun streamObjects(vararg objects: Any): Array<Any> {
        val list = mutableListOf<Any>()
        for (obj in objects) {
            if (obj is IntArray) {
                for (i in obj) list.add(i)
            } else if (obj.javaClass.isArray) {
                val len = java.lang.reflect.Array.getLength(obj)
                for (i in 0 until len) list.add(java.lang.reflect.Array.get(obj, i))
            } else {
                list.add(obj)
            }
        }
        return list.toTypedArray()
    }

    @JvmStatic
    @Throws(ClassNotFoundException::class, java.io.IOException::class)
    fun getClassesWithAnnotation(packageName: String, annotation: Class<out Annotation>): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        io.github.classgraph.ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages(packageName)
            .scan().use { scanResult ->
                for (classInfo in scanResult.getClassesWithAnnotation(annotation.name)) {
                    classes.add(classInfo.loadClass())
                }
            }
        return classes
    }

    @JvmStatic
    @Throws(ClassNotFoundException::class, java.io.IOException::class)
    fun getClasses(packageName: String): List<Class<*>> =
        io.github.classgraph.ClassGraph().enableClassInfo().acceptPackages(packageName).scan().use { scan ->
            scan.allClasses.map { it.loadClass() }
        }

    @JvmStatic
    @Throws(ClassNotFoundException::class, java.io.IOException::class)
    fun getMethodsWithAnnotation(packageName: String, annotation: Class<out Annotation>): List<java.lang.reflect.Method> =
        io.github.classgraph.ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
            .acceptPackages(packageName).scan().use { scan ->
                scan.getClassesWithMethodAnnotation(annotation.name).flatMap { info ->
                    info.loadClass().declaredMethods.filter { it.isAnnotationPresent(annotation) }
                }
            }

    @JvmStatic
    fun splitTextIntoLines(text: String, fontId: Int, width: Int): Array<String> {
        val fonts = Cache.fonts
        if (fontId !in fonts.indices) return arrayOf(text)
        return fonts[fontId].splitLines(text, width).toTypedArray()
    }

    @JvmStatic
    fun getIFStringHeightAbs(text: String, fontId: Int, width: Int): Int =
        splitTextIntoLines(text, fontId, width).size * 15 + 3

    @JvmStatic
    fun <T> add(list: java.util.ArrayList<T>, items: Array<T>?) {
        if (items != null) {
            for (item in items) {
                if (item != null) list.add(item)
            }
        }
    }

    @JvmStatic
    fun getFieldValue(obj: Any, field: java.lang.reflect.Field): Any? {
        field.isAccessible = true
        return field.get(obj)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> getSubClasses(packageName: String, superClass: Class<T>): List<Class<T>> {
        val classes = mutableListOf<Class<T>>()
        io.github.classgraph.ClassGraph()
            .enableClassInfo()
            .acceptPackages(packageName)
            .scan().use { scanResult ->
                for (classInfo in scanResult.getSubclasses(superClass.name)) {
                    if (!java.lang.reflect.Modifier.isAbstract(classInfo.modifiers)) {
                        classes.add(classInfo.loadClass() as Class<T>)
                    }
                }
            }
        return classes
    }
}
