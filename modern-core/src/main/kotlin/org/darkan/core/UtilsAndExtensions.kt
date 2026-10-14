package org.darkan.core

import io.github.classgraph.ClassGraph
import world.gregs.voidps.type.secureRandom
import java.lang.reflect.Method
import java.text.NumberFormat
import java.util.*
import kotlin.math.roundToInt

fun toInterfaceHash(interfaceId: Int, componentId: Int) = interfaceId shl 16 or componentId

private const val FNV1aPrime = 16777619u
fun String.hashToShort(): Short {
    var hash = 0u
    for (char in this) {
        hash = hash xor char.code.toUInt()
        hash = (hash * FNV1aPrime) % 65536u
    }
    return hash.toShort()
}

val currentTimeTicks get() = System.currentTimeMillis() / 600L

fun String.formatPlayerNameForProtocol(): String {
    return this.lowercase().replace(" ", "_")
}

val EMAIL_REGEX = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", RegexOption.IGNORE_CASE)
fun String.isValidEmail() = matches(EMAIL_REGEX)
val USERNAME_REGEX = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9_]{0,10}[a-zA-Z0-9])?$")
fun String.isValidAccountName() = this.length in 1..12 && !this.contains("__") && matches(USERNAME_REGEX)
private val BOT_NAME_REGEX = Regex(".*[ _]bot", RegexOption.IGNORE_CASE)
fun String.isBotName() = matches(BOT_NAME_REGEX)
const val PASSWORD_MIN_LENGTH = 8
val PASSWORD_REGEX = Regex("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{$PASSWORD_MIN_LENGTH,}$")
fun String.isValidPassword() = matches(PASSWORD_REGEX)

private val SECRET_COMMANDS = setOf("changepass", "changepassother", "setpass", "resetpass")

/**
 * A command line with its arguments stripped when they are a password. Every server that logs or
 * persists what a player typed goes through this: the commands only exist on the lobby, but a player
 * types them wherever they happen to be, and a world - a community world above all - must never end
 * up holding a plaintext password.
 */
fun String.redactedCommand(): String {
    val parts = trim().split(" ")
    val name = parts.first().lowercase()
    if (name !in SECRET_COMMANDS) return this
    return "$name <${parts.size - 1} args>"
}

enum class PasswordProblem { TOO_SHORT, NOT_ALPHANUMERIC, TOO_EASY }

fun String.passwordProblem(): PasswordProblem? = when {
    isValidPassword() -> null
    length < PASSWORD_MIN_LENGTH -> PasswordProblem.TOO_SHORT
    !all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } -> PasswordProblem.NOT_ALPHANUMERIC
    else -> PasswordProblem.TOO_EASY
}

fun String.formatPlayerNameForDisplay(): String {
    return this.replace("_", " ")
        .lowercase()
        .split(" ")
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
}

/**
 * Formats a chat message: capitalizes first letter, auto-capitalizes after sentence
 * punctuation (.!?:;), preserves uppercase at start of words after spaces, and lowercases
 * mid-word uppercase letters. Matches legacy Utils.fixChatMessage behavior.
 */
fun String.fixChatMessage(): String {
    val sb = StringBuilder(length)
    var space = false
    var forcedCaps = true
    for (ch in this) {
        if (forcedCaps) {
            if (ch == ' ' || ch == ':' || ch == ';') {
                sb.append(ch)
            } else {
                sb.append(ch.uppercaseChar())
                forcedCaps = false
            }
        } else {
            when {
                ch == '?' || ch == '!' || ch == '.' || ch == ':' || ch == ';' -> {
                    forcedCaps = true
                    sb.append(ch)
                }
                ch == ' ' -> {
                    space = true
                    sb.append(ch)
                }
                ch.isUpperCase() -> {
                    if (space) {
                        sb.append(ch)
                        space = false
                    } else {
                        sb.append(ch.lowercaseChar())
                    }
                }
                else -> {
                    sb.append(ch)
                    space = false
                }
            }
        }
    }
    return sb.toString()
}

fun generateRandomString(length: Int = 50): String {
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { charPool.random() }
        .joinToString("")
}

/**
 * Tick time extensions
 */

fun Double.ticksToTimeString(): String = this.roundToInt().ticksToTimeString()
fun Int.ticksToTimeString(): String = this.toLong().ticksToTimeString()
fun Long.ticksToTimeString(): String = toMillisTimeString(this * 600)

private fun toMillisTimeString(millis: Long): String {
    var seconds = (millis / 1000).toInt()
    var minutes = seconds / 60
    val hours = minutes / 60

    minutes -= hours * 60
    seconds -= (hours * 60 * 60) + (minutes * 60)

    val parts = mutableListOf<String>()
    if (hours > 0) parts += "$hours hours"
    if (minutes > 0) parts += "$minutes minutes"
    if (seconds > 0) parts += "$seconds seconds"

    return (parts.joinToString(" ").ifEmpty { "moment or two" } + ".")
}

fun Int.formatNumber() = NumberFormat.getNumberInstance(Locale.US).format(toLong())
fun Long.formatNumber() = NumberFormat.getNumberInstance(Locale.US).format(this)
fun Double.formatNumber() = NumberFormat.getNumberInstance(Locale.US).format(toLong())

fun generateRandom24ByteArray(): ByteArray = ByteArray(24).apply { secureRandom.nextBytes(this) }

/**
 * Finds all methods with a specific annotation in the given package
 */
fun getMethodsWithAnnotation(packageName: String, annotation: Class<out Annotation>): List<Method> =
    ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .acceptPackages(packageName)
        .scan().use { scanResult ->
            scanResult.allClasses
                .flatMap { it.methodInfo }
                .filter { it.hasAnnotation(annotation.name) }
                .map { it.loadClassAndGetMethod() }
        }

/**
 * Finds all classes with a specific annotation in the given package
 */
fun getClassesWithAnnotation(packageName: String, annotation: Class<out Annotation>): List<Class<*>> =
    ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages(packageName)
        .scan().use { scanResult ->
            scanResult.getClassesWithAnnotation(annotation.name)
                .map { it.loadClass() }
        }

/**
 * Gets all classes in the given package
 */
fun getClasses(packageName: String): List<Class<*>> =
    ClassGraph()
        .enableClassInfo()
        .acceptPackages(packageName)
        .scan().use { scanResult ->
            scanResult.allClasses
                .map { it.loadClass() }
        }

/**
 * Gets all subclasses of a specific class in the given package
 */
fun getSubClasses(packageName: String, superClass: Class<*>): List<Class<*>> =
    ClassGraph()
        .enableClassInfo()
        .acceptPackages(packageName)
        .scan().use { scanResult ->
            scanResult.getSubclasses(superClass.name)
                .map { it.loadClass() }
        }