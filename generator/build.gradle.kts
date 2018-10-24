description = "Utility to generate Kotlin classes to interface with a database"

dependencies {
	//So, the java gradle plugin for inexplicable reasons created a default maven publication called "pluginMaven", which
	//cannot be customized in the way I'd like. And also, if there's more than one publication in a project, Gradle has no
	//real mechanism for disambiguating which jar to use. So...
	//Uncomment the :core dependency while developing, uncomment the Maven one while publishing. :)
	compile("net.justmachinery.kdbgen:kdbgen-core:$version")
	//compile(project(":core"))
	implementation("org.reflections:reflections:0.9.10")
	implementation("org.postgresql:postgresql:42.2.5")
	implementation("org.jetbrains.kotlin:kotlin-reflect:1.2.61")
	implementation("com.google.auto.service:auto-service:1.0-rc4")
	kapt("com.google.auto.service:auto-service:1.0-rc4")
}
