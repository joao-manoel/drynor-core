package org.darkan.core.net.web

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.forms.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.darkan.core.EnvVars

object DiscordWebhook {
    val client = HttpClient(CIO)

    fun sendStaffMessage(content: String) {
        if (EnvVars.staffWebhookUrl == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                client.submitFormWithBinaryData(
                    url = EnvVars.staffWebhookUrl,
                    formData = formData { append("content", content) }
                )
            } catch (_: Exception) {
                // Silently fail, as it's only for logging
            }
        }
    }
}