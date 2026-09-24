plugins { id("mulcor.java") }

description = "Vanilla registry tables (block states, shapes, items, entity types, attributes) generated at build time."

// The generator reads Minestom's bundled vanilla data (net.minestom:data) at build time. The runtime module has no
// dependencies: it loads one binary table and exposes it as static final primitive arrays.
val gen: SourceSet = sourceSets.create("gen")
dependencies {
    "genImplementation"(libs.minestom)
    testImplementation(libs.minestom) // oracle: every generated value is checked against Minestom's registry
}

val generatedDir = layout.buildDirectory.dir("generated/registry")
val generateRegistry by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates registry.bin and the *Id constant classes from vanilla data."
    classpath = gen.runtimeClasspath
    mainClass = "dev.mulcor.registry.gen.RegistryGen"
    jvmArgs("--enable-preview")
    val out = generatedDir
    outputs.dir(out)
    inputs.files(gen.runtimeClasspath)
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.absolutePath) })
}

sourceSets.main {
    java.srcDir(generatedDir.map { it.dir("java") })
    resources.srcDir(generatedDir.map { it.dir("resources") })
}
tasks.named("compileJava") { dependsOn(generateRegistry) }
tasks.named("processResources") { dependsOn(generateRegistry) }
