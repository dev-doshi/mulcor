import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

// Preview class files are tied to the exact compiler release, so compile and run on one JDK.
val javaRelease = providers.gradleProperty("mulcor.java.release").map(String::toInt).orElse(27)

java {
    toolchain {
        languageVersion = javaRelease.map(JavaLanguageVersion::of)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--enable-preview", "-parameters", "-Xlint:all,-preview,-processing,-serial,-options"))
}

tasks.withType<Javadoc>().configureEach { enabled = false }

// ---- Extra source sets: JMH benchmarks and jcstress concurrency tests -------------------------------------------

val main: SourceSet = sourceSets["main"]
val jmh: SourceSet = sourceSets.create("jmh") {
    compileClasspath += main.output
    runtimeClasspath += main.output
}
val jcstress: SourceSet = sourceSets.create("jcstress") {
    compileClasspath += main.output
    runtimeClasspath += main.output
}
for (set in listOf(jmh, jcstress)) {
    configurations[set.implementationConfigurationName].extendsFrom(configurations["implementation"])
    configurations[set.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])
}

dependencies {
    "testImplementation"(platform(lib("junit-bom")))
    "testImplementation"(lib("junit-jupiter"))
    "testRuntimeOnly"(lib("junit-launcher"))

    "jmhImplementation"(lib("jmh-core"))
    "jmhAnnotationProcessor"(lib("jmh-annprocess"))

    "jcstressImplementation"(lib("jcstress-core"))
    "jcstressAnnotationProcessor"(lib("jcstress-core"))
}

// ---- Shared "one perf task at a time" gate ----------------------------------------------------------------------

abstract class ExclusiveCpu : BuildService<BuildServiceParameters.None>

val exclusiveCpu = gradle.sharedServices.registerIfAbsent(MulcorJvm.EXCLUSIVE_CPU_SERVICE, ExclusiveCpu::class) {
    maxParallelUsages = 1
}
// Perf tasks must never overlap functional tests of any module, or their timings are meaningless.
val allFunctionalTests = rootProject.subprojects.map { "${it.path}:test" }

// ---- Tests -------------------------------------------------------------------------------------------------------

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(MulcorJvm.FLAGS)
    maxHeapSize = "2g"
    systemProperty("mulcor.strict", providers.gradleProperty("strict").isPresent)
    providers.gradleProperty("mulcor.workers").orNull?.let { systemProperty("mulcor.workers", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = providers.gradleProperty("mulcor.verbose").isPresent
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("stress", "alloc") }
}

fun registerTaggedTest(name: String, tag: String, description: String, configure: Test.() -> Unit = {}) =
    tasks.register<Test>(name) {
        group = "verification"
        this.description = description
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags(tag) }
        failOnNoDiscoveredTests = false
        usesService(exclusiveCpu)
        mustRunAfter(allFunctionalTests)
        outputs.upToDateWhen { false }
        configure()
    }

val stressTest = registerTaggedTest("stressTest", "stress", "Tick-budget and anti-duplication stress gates.")
val allocTest = registerTaggedTest("allocTest", "alloc", "Zero-allocation gate for the steady-state tick loop.")
val allocTestEpsilon = registerTaggedTest("allocTestEpsilon", "alloc", "Zero-allocation gate re-run under Epsilon GC.") {
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC")
    maxHeapSize = providers.gradleProperty("mulcor.epsilonHeap").getOrElse("512m")
    systemProperty("mulcor.epsilon", true)
    mustRunAfter(allocTest)
}

// ---- jcstress ----------------------------------------------------------------------------------------------------

val jcstressSources: FileCollection = files(jcstress.allJava)

fun registerJcstress(name: String, mode: String, description: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    this.description = description
    classpath = jcstress.runtimeClasspath.filter { it.exists() } // jcstress's classpath scanner NPEs on missing dirs
    mainClass = "org.openjdk.jcstress.Main"
    // jcstress forks inherit this VM's input arguments, so MulcorJvm.FLAGS reach every forked test VM.
    jvmArgs(MulcorJvm.FLAGS)
    val reportDir = layout.buildDirectory.dir("reports/jcstress")
    val workDir = layout.buildDirectory.dir("jcstress")
    workingDir(workDir)
    outputs.dir(reportDir)
    outputs.upToDateWhen { false }
    val filter = providers.gradleProperty("jcstress.filter")
    doFirst { workDir.get().asFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(listOf("-r", reportDir.get().asFile.absolutePath, "-m", mode))
            filter.orNull?.let { addAll(listOf("-t", it)) }
        }
    })
    usesService(exclusiveCpu)
    mustRunAfter(allFunctionalTests)
    val sources = jcstressSources
    onlyIf("has jcstress sources") { !sources.isEmpty }
}

val jcstressQuick = registerJcstress("jcstressQuick", "quick", "jcstress in quick mode (part of check).")
registerJcstress("jcstressRun", "default", "Full jcstress run.")

// ---- JMH ---------------------------------------------------------------------------------------------------------

val jmhSources: FileCollection = files(jmh.allJava)

tasks.register<JavaExec>("jmhRun") {
    group = "benchmark"
    description = "Runs JMH benchmarks. -Pjmh.include=<regex>, -Pjmh.args='<extra JMH args>'."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    jvmArgs(MulcorJvm.FLAGS)
    val resultFile = layout.buildDirectory.file("reports/jmh/results.json")
    outputs.file(resultFile)
    outputs.upToDateWhen { false }
    val include = providers.gradleProperty("jmh.include")
    val extra = providers.gradleProperty("jmh.args")
    doFirst { resultFile.get().asFile.parentFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            include.orNull?.let { add(it) }
            addAll(listOf("-rf", "json", "-rff", resultFile.get().asFile.absolutePath))
            addAll(listOf("-prof", "gc")) // always report gc.alloc.rate.norm (B/op)
            addAll(listOf("-jvmArgsAppend", MulcorJvm.FLAGS.joinToString(" ")))
            extra.orNull?.let { addAll(it.trim().split(Regex("\\s+"))) }
        }
    })
    usesService(exclusiveCpu)
    mustRunAfter(allFunctionalTests)
    val sources = jmhSources
    onlyIf("has JMH sources") { !sources.isEmpty }
}

tasks.named("check") {
    dependsOn(stressTest, allocTest, allocTestEpsilon, jcstressQuick)
}

// A hung concurrency test must fail the build, not stall it. -Pmulcor.jvmArgs='...' passes diagnostics through.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("mulcor.jvmArgs").orNull?.let { jvmArgs(it.trim().split(Regex("\\s+"))) }
    systemProperty("junit.jupiter.execution.timeout.default", providers.gradleProperty("mulcor.testTimeout").getOrElse("5m"))
}
