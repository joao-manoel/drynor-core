// The CS2 clientscript toolchain: decompiler, TypeScript compiler, virtual machine.
//
// Headless on purpose. The tools module hosts the CLI and the interface editor and so drags in
// LWJGL, GLFW and ImGui; the server has to compile clientscripts too - index 12 of a cache source
// tree is stored as the TypeScript this module emits (see docs/cache-source.md) - and must not
// link a windowing toolkit to do it. Nothing here may depend on :tools, and nothing here may
// depend on a GL, GLFW or ImGui artifact.
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}
