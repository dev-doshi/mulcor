plugins { id("mulcor.java") }

description = "Regionized lock-free tick engine: Hilbert grid, region scheduler, messaging, mechanics."

dependencies {
    api(project(":mulcor-memory"))
    api(project(":mulcor-registry"))
    testImplementation(project(":mulcor-storage")) // real vanilla chunks as test oracles (light, blocks)
}
