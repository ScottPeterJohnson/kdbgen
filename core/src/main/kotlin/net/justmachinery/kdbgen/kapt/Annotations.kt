package net.justmachinery.kdbgen.kapt

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GeneratePostgresInterface

annotation class SqlGenerationSettings(
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
    val enumPackage : String = "net.justmachinery.kdbgen.enums",
    /**
     * Package to output beans and DSL to
     */
    val dataPackage : String = "net.justmachinery.kdbgen.tables",
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

@Suppress("DEPRECATED_JAVA_ANNOTATION")
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY
)
@Repeatable
@java.lang.annotation.Repeatable(SqlQueries::class)
@Retention(AnnotationRetention.SOURCE)
annotation class SqlQuery(
    val name : String,
    val query : String
)

@Retention(AnnotationRetention.SOURCE)
annotation class SqlQueries(vararg val value : SqlQuery)