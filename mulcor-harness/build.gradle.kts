plugins { id("mulcor.java") }

description = "Headless virtual clients, bot scenarios, metrics, stress/alloc gates and benchmarks."

dependencies {
    api(project(":mulcor-core"))
    api(project(":mulcor-net"))
    implementation(libs.hdrhistogram)
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
