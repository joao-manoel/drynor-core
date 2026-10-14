package org.darkan.tools.engine.window

data class WindowConfig(
    val title: String = "Darkan Tools",
    val width: Int = 1280,
    val height: Int = 720,
    /**
     * V-Sync, on by default.
     *
     * `-Ddarkan.vsync=false` uncaps the frame rate. With V-Sync on, frame rate saturates
     * at the display refresh and stops being a usable measurement, so performance
     * comparisons want it off (or want the GPU pass timers instead).
     */
    val vsync: Boolean = System.getProperty("darkan.vsync", "true").toBoolean(),
    val resizable: Boolean = true,
    val samples: Int = 4
)
