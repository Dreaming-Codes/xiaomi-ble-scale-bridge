plugins { kotlin("jvm") }
dependencies {
    implementation(project(":protocol"))
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
