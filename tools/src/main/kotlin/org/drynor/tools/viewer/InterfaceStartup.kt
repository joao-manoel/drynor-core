package org.drynor.tools.viewer

import java.io.File

data class InterfaceStartup(
    val source: File? = null,
    val packedOnly: Boolean = false,
    val vars: List<String> = emptyList(),
    val stats: List<String> = emptyList(),
    val runs: List<String> = emptyList(),
)
