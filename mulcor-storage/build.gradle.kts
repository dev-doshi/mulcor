plugins { id("mulcor.java") }

description = "World persistence: streaming NBT, Anvil region files, chunk (de)serialization."

dependencies {
    api(project(":mulcor-memory"))
    api(project(":mulcor-registry"))
    testImplementation(libs.minestom) // Adventure NBT is the byte-level oracle for the NBT codec
}
