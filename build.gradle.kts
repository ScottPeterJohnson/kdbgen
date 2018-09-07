import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.util.Date
import java.net.URI

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
version = "0.4.6"

repositories {
	mavenCentral()
	jcenter()
	maven { url = URI("https://dl.bintray.com/scottpjohnson/generic/") }
}

publishing {
	(publications) {
		create<MavenPublication>("kdbgen") {
			from(components["java"])
			groupId = "net.justmachinery.kdbgen"
			artifactId = name
			version = project.version as String?
			artifact(tasks.getByName("sourcesJar"))
		}
	}
}

bintray {
	user = project.property("BINTRAY_USER") as String?
	key = project.property("BINTRAY_KEY") as String?

	val pkgOps = closureOf<BintrayExtension.PackageConfig> {
		repo = "generic"
		name = "kdbgen"
		vcsUrl = "https://github.com/ScottPeterJohnson/kdbgen.git"
		version(closureOf<BintrayExtension.VersionConfig> {
			name = project.version as String?
			desc = "$project.name version $project.version"
			released = Date().toString()
			vcsTag = "$project.version"
		})
		setProperty("licenses", arrayOf("Apache-2.0"))
	}
	pkg(pkgOps)
	this.setProperty("publications", arrayOf("kdbgen"))
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
		args("--useCommonTypes")
		args("--mutableData")
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
