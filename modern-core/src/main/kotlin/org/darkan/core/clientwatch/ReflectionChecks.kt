package org.darkan.core.clientwatch

enum class ReflectionResponseCode(val id: Int) {
    SUCCESS(0),
    NUMBER(1),
    STRING(2),
    OTHER(4),

    CHECK_EXCEPTION_CLASS_NOT_FOUND(-1),
    CHECK_EXCEPTION_SECURITY(-2),
    CHECK_EXCEPTION_NULLPOINTER(-3),
    CHECK_EXCEPTION(-4),
    CHECK_THROWABLE(-5),

    RESP_EXCEPTION_CLASS_NOT_FOUND(-10),
    RESP_EXCEPTION_INVALID_CLASS(-11),
    RESP_EXCEPTION_STREAM_CORRUPTED(-12),
    RESP_EXCEPTION_OPTIONAL_DATA(-13),
    RESP_EXCEPTION_ILLEGAL_ACCESS(-14),
    RESP_EXCEPTION_ILLEGAL_ARGUMENT(-15),
    RESP_EXCEPTION_INVOCATION_TARGET(-16),
    RESP_EXCEPTION_SECURITY(-17),
    RESP_EXCEPTION_IO(-18),
    RESP_EXCEPTION_NULLPOINTER(-19),
    RESP_EXCEPTION(-20),
    RESP_THROWABLE(-21);

    companion object {
        private val map = entries.associateBy { it.id }
        fun fromId(id: Int) = map[id]
    }
}

enum class ReflectionCheckType {
    GET_INT,
    SET_INT,
    GET_FIELD_MODIFIERS,
    GET_METHOD_RETURN_VALUE,
    GET_METHOD_MODIFIERS
}

data class ReflectionData(
    val numericalData: Long? = null,
    val stringData: String? = null
)

data class ReflectionCheck(
    val type: ReflectionCheckType,
    val className: String,
    val methodName: String,
    val returnType: String? = null,
    val paramTypes: Array<String> = emptyArray(),
    val paramValues: Array<Any> = emptyArray(),
    val fieldValue: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReflectionCheck

        if (fieldValue != other.fieldValue) return false
        if (type != other.type) return false
        if (className != other.className) return false
        if (methodName != other.methodName) return false
        if (returnType != other.returnType) return false
        if (!paramTypes.contentEquals(other.paramTypes)) return false
        if (!paramValues.contentEquals(other.paramValues)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fieldValue ?: 0
        result = 31 * result + type.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + methodName.hashCode()
        result = 31 * result + (returnType?.hashCode() ?: 0)
        result = 31 * result + paramTypes.contentHashCode()
        result = 31 * result + paramValues.contentHashCode()
        return result
    }
}