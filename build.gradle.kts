import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet


buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.2.61")
	}
}
plugins {
	`java-gradle-plugin`
	maven
	`maven-publish`
	kotlin("jvm").version("1.2.61")
	id("com.jfrog.bintray").version("1.8.4")
}
group = "net.justmachinery.kdbgen"
description = "Utility to generate Kotlin classes to interface with a database"

repositories {
	mavenCentral()
	jcenter()
}

publishing {
	publications {
		creating(MavenPublication::class) {
			from(components["java"])
			groupId = "net.justmachinery.kdbgen"
			artifactId = name
			version = "0.4.1"
			artifact(tasks.getByName("sourcesJar"))
		}
	}
}

java.sourceSets["test"].withConvention(KotlinSourceSet::class) {
	kotlin.srcDir(file("build/generated-sources/kotlin"))
}

tasks {
	"sourcesJar"(Jar::class) {
		classifier = "sources"
		from(java.sourceSets["main"].allSource)
	}

	"generatePostgresInterface"(JavaExec::class) {
		classpath = java.sourceSets["test"].compileClasspath
		main = "net.justmachinery.kdbgen.generation.GeneratePostgresInterfaceKt"
		args("--databaseUrl=jdbc:postgresql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest")
		args("--enumPackage=net.justmachinery.kdbgen.test.generated.enums")
		args("--dataPackage=net.justmachinery.kdbgen.test.generated.tables")
	}

	getByName("compileTestKotlin").dependsOn.add("generatePostgresInterface")
}


dependencies {
	compile("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.2.61")
	compile("org.reflections:reflections:0.9.10")
	compile("org.postgresql:postgresql:9.4.1212")
	compile("org.jetbrains.kotlin:kotlin-reflect:1.2.61")
	compile("com.xenomachina:kotlin-argparser:2.0.0")
	testCompile("io.kotlintest:kotlintest:2.0.3")
}
