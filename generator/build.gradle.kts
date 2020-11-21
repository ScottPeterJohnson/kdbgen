description = "Utility to generate Kotlin classes to interface with a database"

dependencies {
	api(project(":core"))
	implementation("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.6")
	implementation("com.squareup:kotlinpoet:1.7.2")
    implementation("org.apache.commons:commons-lang3:3.11")
}
