plugins {
    application
    kotlin("plugin.serialization")
}

val lwjglVersion = "3.4.3"
val lwjglNatives = Pair(
    System.getProperty("os.name")!!,
    System.getProperty("os.arch")!!
).let { (name, arch) ->
    when {
        "Windows" in name -> "natives-windows"
        "Mac" in name || "Darwin" in name -> if ("aarch64" in arch) "natives-macos-arm64" else "natives-macos"
        else -> if ("aarch64" in arch || "arm" in arch) "natives-linux-arm64" else "natives-linux"
    }
}

application {
    mainClass.set("org.drynor.tools.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    // The CS2 toolchain, which is headless and shared with the server; see cs2/build.gradle.kts.
    implementation(project(":cs2"))

    // LWJGL BOM
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    // LWJGL modules
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-stb")

    // Native libraries
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")

    // Math library
    implementation("org.joml:joml:1.10.9")

    // ImGui for debug UI
    implementation("io.github.spair:imgui-java-binding:1.92.7.1")
    implementation("io.github.spair:imgui-java-lwjgl3:1.92.7.1")
    runtimeOnly("io.github.spair:imgui-java-natives-linux:1.92.7.1")
}
