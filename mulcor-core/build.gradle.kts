plugins { id("mulcor.java") }

description = "Regionized lock-free tick engine: Hilbert grid, region scheduler, messaging, mechanics."

dependencies {
    api(project(":mulcor-memory"))
}
