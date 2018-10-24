description = "Utility to generate Kotlin classes to interface with a database"

dependencies {
    implementation("org.reflections:reflections:0.9.10")
    compileOnly("org.postgresql:postgresql:42.2.5")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.2.61")
}
