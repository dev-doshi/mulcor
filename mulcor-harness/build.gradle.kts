plugins { id("mulcor.java") }

description = "Headless virtual clients, bot scenarios, metrics, stress/alloc gates and benchmarks."

dependencies {
    api(project(":mulcor-core"))
    api(project(":mulcor-net"))
    testImplementation(project(":mulcor-registry"))
    testImplementation(libs.archunit)
    implementation(libs.hdrhistogram)
    implementation(libs.minestom) // VanillaClient speaks the client side of the protocol with Minestom's serializers
    "jmhImplementation"(libs.jctools)
    "jmhImplementation"(libs.disruptor)
}

tasks.named<Test>("allocTestEpsilon") {
    maxHeapSize = providers.gradleProperty("mulcor.epsilonHeap").getOrElse("1g") // Epsilon never frees: size for warm-up
}

val report by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the benchmark scenarios and writes build/reports/mulcor/summary.{md,json}."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mulcor.harness.ReportMain"
    jvmArgs(MulcorJvm.FLAGS)
    maxHeapSize = "2g"
    val out = rootProject.layout.buildDirectory.dir("reports/mulcor")
    val root = rootProject.layout.projectDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.absolutePath, root.asFile.absolutePath) })
    outputs.dir(out)
    outputs.upToDateWhen { false }
    usesService(gradle.sharedServices.registrations.getByName(MulcorJvm.EXCLUSIVE_CPU_SERVICE).service)
    rootProject.subprojects.forEach { p -> mustRunAfter("${p.path}:jmhRun", "${p.path}:jcstressRun", "${p.path}:check") }
}

val runServer by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Starts a Mulcor server real Minecraft clients can join (default port 25565). Pass options with --args."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mulcor.harness.MulcorServer"
    jvmArgs(MulcorJvm.FLAGS)
    maxHeapSize = "2g"
    standardInput = System.`in`
}

val vanillaProbe by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Joins a running server as a headless vanilla client and reports what it received (--args='host port')."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mulcor.harness.VanillaClient"
    jvmArgs(MulcorJvm.FLAGS)
}
