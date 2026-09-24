/**
 * JVM flags every Mulcor JVM must run with: tests, JavaExec tasks, and JMH/jcstress forked VMs.
 * Class files are compiled with --enable-preview, so no VM can load them without it.
 */
object MulcorJvm {
    val FLAGS: List<String> = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
    )

    /** Performance-sensitive tasks (stress, alloc, JMH, jcstress) share this so they never contend for cores. */
    const val EXCLUSIVE_CPU_SERVICE = "mulcorExclusiveCpu"
}
