import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	`java-gradle-plugin`
	maven
	`maven-publish`
	signing
	val kotlinVersion = "1.4.31"
	kotlin("jvm").version(kotlinVersion)
	id("org.jetbrains.kotlin.kapt").version(kotlinVersion)
}

allprojects {
	version = "0.9.8"
	group = "net.justmachinery.kdbgen"


	repositories {
		mavenCentral()
		jcenter()
	}

}
subprojects {
	apply(plugin = "org.gradle.maven")
	apply(plugin = "org.gradle.maven-publish")
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.kapt")
	apply(plugin = "org.gradle.signing")

	val sourcesJar by tasks.registering(Jar::class){
		archiveClassifier.set("sources")
		from(sourceSets.main.get().allSource)
	}
	val javadocJar by tasks.registering(Jar::class){
		dependsOn.add(JavaPlugin.JAVADOC_TASK_NAME)
		archiveClassifier.set("javadoc")
		from(tasks.getByName("javadoc"))
	}

	artifacts {
		archives(sourcesJar)
		archives(javadocJar)
	}

	val projectName = name
	publishing {
		publications {
			create<MavenPublication>("mavenKotlin") {
				artifactId = "kdbgen-$projectName"
				from(components["kotlin"])
				pom {
					name.set("Kdbgen $projectName")
					description.set("$description")
					url.set("https://github.com/ScottPeterJohnson/kdbgen")
					licenses {
						license {
							name.set("The Apache License, Version 2.0")
							url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
						}
					}
					developers {
						developer {
							id.set("scottj")
							name.set("Scott Johnson")
							email.set("mavenkdbgen@justmachinery.net")
						}
					}
					scm {
						connection.set("scm:git:git://github.com/ScottPeterJohnson/kdbgen.git")
						developerConnection.set("scm:git:ssh://github.com/ScottPeterJohnson/kdbgen.git")
						url.set("http://github.com/ScottPeterJohnson/kdbgen")
					}
				}
				artifact(sourcesJar)
				artifact(javadocJar)
			}
		}
		repositories {
			maven {
				name = "central"
				val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
				val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
				url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
				credentials {
					username = findProperty("ossrhUsername") as? String
					password = findProperty("ossrhPassword") as? String
				}
			}
		}
	}

	signing {
		sign(publishing.publications["mavenKotlin"])
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
	implementation(project(":core"))
	kapt(project(":generator"))
	kaptTest(project(":generator"))
	implementation("com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.6")
	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.1.8")
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
}

description = "Testing master project for kdbgen"