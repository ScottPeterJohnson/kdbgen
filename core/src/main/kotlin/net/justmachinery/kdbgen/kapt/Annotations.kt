package net.justmachinery.kdbgen.kapt

import net.justmachinery.kdbgen.defaultDataPackage
import net.justmachinery.kdbgen.defaultEnumPackage

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GeneratePostgresInterface(
    /**
     * URL of database to connect to (including user/pass)
     */
    val databaseUrl : String,
    /**
     * Directory to output generated source files to
     * Defaults to kapt
     */
    val outputDirectory : String = "",
    /**
     * Directory to output DSL helpers to, if different than output directory
     */
    val dslOutputDirectory : String = "",
    /**
     * Outputs common JS/JVM types instead of UUID/Timestamp.
     */
    val useCommonTypes : Boolean = false,
    /**
     * Package to output enum classes to
     */
    val enumPackage : String = defaultEnumPackage,
    /**
     * Package to output beans and DSL to
     */
    val dataPackage : String = defaultDataPackage,
    /**
     * Fully qualified annotations to add to emitted data classes, for e.g. serialization
     * Note that kapt only has one round, so annotations that themselves generate sources won't have an effect
     */
    val dataAnnotation : Array<String> = [],
    /**
     * Whether to generate properties on data classes as var instead of val
     */
    val mutableData : Boolean = false
)