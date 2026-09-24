// Aggregate entry point: `./gradlew fullValidation` = every module's check + full jcstress + JMH + metrics report.
tasks.register("fullValidation") {
    group = "verification"
    description = "check + full jcstress + JMH across all modules."
    dependsOn(subprojects.flatMap { p -> listOf("check", "jcstressRun", "jmhRun").map { "${p.path}:$it" } })
    dependsOn(":mulcor-harness:report")
}
