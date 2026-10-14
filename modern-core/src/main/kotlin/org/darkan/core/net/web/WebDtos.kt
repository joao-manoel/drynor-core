package org.darkan.core.net.web

import io.ktor.server.plugins.requestvalidation.*
import kotlinx.serialization.Serializable
import org.darkan.core.isValidAccountName
import org.darkan.core.isValidEmail
import org.darkan.core.isValidPassword

fun RequestValidationConfig.installCoreValidators() {
    validateAccountCreate()
}

@Serializable
data class AccountCreateRequest(val username: String, val email: String, val password: String)

private fun RequestValidationConfig.validateAccountCreate() {
    validate<AccountCreateRequest> { request ->
        when {
            !request.username.isValidAccountName() ->
                ValidationResult.Invalid("Username must be 1-16 alphanumeric characters, may include spaces but not consecutive spaces or spaces alone")
            !request.email.isValidEmail() ->
                ValidationResult.Invalid("Invalid email format")
            !request.password.isValidPassword() ->
                ValidationResult.Invalid("Password must be at least 8 characters and contain at least one letter and one number")
            else -> ValidationResult.Valid
        }
    }
}