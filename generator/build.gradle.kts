description = "Utility to generate Kotlin classes to interface with a database"

dependencies {
	//So, the java gradle plugin for inexplicable reasons created a default maven publication called "pluginMaven", which
	//cannot be customized in the way I'd like. And also, if there's more than one publication in a project, Gradle has no
	//real mechanism for disambiguating which jar to use. So...
	//Uncomment the :core dependency while developing, uncomment the Maven one while publishing. :)
	//compile("net.justmachinery.kdbgen:kdbgen-core:$version")
	compile(project(":core"))
	implementation("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.6")
	implementation("com.squareup:kotlinpoet:1.7.2")
    implementation("org.apache.commons:commons-lang3:3.11")
}
