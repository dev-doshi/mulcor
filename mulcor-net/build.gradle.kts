plugins { id("mulcor.java") }

description = "Netty ingestion into region rings, token-bucket backpressure, Minestom-backed encoding."

dependencies {
    api(project(":mulcor-core"))
    implementation(libs.minestom)
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.buffer)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport)
    runtimeOnly(variantOf(libs.netty.transport.kqueue) { classifier("osx-aarch_64") })
    runtimeOnly(variantOf(libs.netty.transport.epoll) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.netty.transport.epoll) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.kqueue)
    implementation(libs.netty.transport.epoll)
}
