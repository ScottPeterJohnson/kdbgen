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
	id("kotlin-platform-common")
	id("com.jfrog.bintray")
}
group = "net.justmachinery.kdbgen.common"
description = "Common JS/JVM classes for kdbgen"

repositories {
	mavenCentral()
	jcenter()
}

publishing {
	publications {
		creating(MavenPublication::class) {
			from(components["java"])
			groupId = "net.justmachinery.kdbgen.common"
			artifactId = name
			version = "0.4.1"
			artifact(tasks.getByName("sourcesJar"))
		}
	}
}

tasks {
	"sourcesJar"(Jar::class) {
		classifier = "sources"
		from(java.sourceSets["main"].allSource)
	}
}


dependencies {
	compile("org.jetbrains.kotlin:kotlin-stdlib-common:1.2.61")
	testCompile("org.jetbrains.kotlin:kotlin-test-common:1.2.61")
}
