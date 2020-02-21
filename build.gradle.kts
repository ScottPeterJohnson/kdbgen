
import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI
import java.util.*

plugins {
	`java-gradle-plugin`
	maven
	`maven-publish`
	kotlin("jvm").version("1.3.61")
	id("com.jfrog.bintray").version("1.8.4")
	id("org.jetbrains.kotlin.kapt").version("1.3.61")
}

allprojects {
	version = "0.8.12"
	group = "net.justmachinery.kdbgen"


	repositories {
		mavenCentral()
		jcenter()
		maven { url = URI("https://dl.bintray.com/scottpjohnson/generic/") }
	}

}
subprojects {
	apply(plugin = "org.gradle.java-gradle-plugin")
	apply(plugin = "org.gradle.maven")
	apply(plugin = "org.gradle.maven-publish")
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "com.jfrog.bintray")
	apply(plugin = "org.jetbrains.kotlin.kapt")


    tasks.register<Jar>("sourcesJar") {
        classifier = "sources"
        from(sourceSets["main"].allSource)
    }

	publishing {
		(publications) {
			create<MavenPublication>("kdbgen-$name") {
				from(components["java"])
				groupId = group as String
				artifactId = name
				version = project.version as String?
				artifact(tasks.getByName("sourcesJar"))
			}
		}
	}

	bintray {
		user = project.property("BINTRAY_USER") as String?
		key = project.property("BINTRAY_KEY") as String?
		publish = true

		val pkgOps = closureOf<BintrayExtension.PackageConfig> {
			repo = "generic"
			name = "kdbgen-${project.name}"
			vcsUrl = "https://github.com/ScottPeterJohnson/kdbgen.git"
			version(closureOf<BintrayExtension.VersionConfig> {
				name = project.version as String?
				desc = "${project.name} version ${project.version}"
				released = Date().toString()
				vcsTag = "${project.version}"
			})
			setProperty("licenses", arrayOf("Apache-2.0"))
		}
		pkg(pkgOps)
		this.setProperty("publications", arrayOf("kdbgen-$name"))
	}

}

allprojects {
	val compileKotlin : KotlinCompile by tasks
	compileKotlin.kotlinOptions {
		jvmTarget = JavaVersion.VERSION_11.toString()
	}
	val compileTestKotlin : KotlinCompile by tasks
	compileTestKotlin.kotlinOptions {
		jvmTarget = JavaVersion.VERSION_11.toString()
	}
	dependencies {
        implementation(kotlin("stdlib-jdk8"))
	}
}

val test by tasks.getting(Test::class) {
	useJUnitPlatform()
}

dependencies {
	testCompile(project(":generator"))
	kaptTest(project(":generator"))
	testCompile("org.postgresql:postgresql:42.2.5")
	testCompile("io.kotlintest:kotlintest-runner-junit5:3.1.8")
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
	implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.10")
}

description = "Testing master project for kdbgen"