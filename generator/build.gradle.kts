description = "Utility to generate Kotlin classes to interface with a database"

dependencies {
	api(project(":core"))
	implementation("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9")
	implementation("com.squareup:kotlinpoet:1.16.0")
    implementation("org.apache.commons:commons-lang3:3.14.0")
	implementation("com.google.devtools.ksp:symbol-processing-api:1.9.22-1.0.17")
}
