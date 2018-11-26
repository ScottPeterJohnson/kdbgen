package net.justmachinery.kdbgen.kapt

import org.intellij.lang.annotations.Language

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
     * Package to output beans and DSL to
     */
    val outputPackage : String = "net.justmachinery.kdbgen"
)

@Suppress("DEPRECATED_JAVA_ANNOTATION")
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Repeatable
@java.lang.annotation.Repeatable(SqlQueries::class)
@Retention(AnnotationRetention.SOURCE)
annotation class SqlQuery(
    /**
     * Name of the query function to generate/
     */
    val name : String,
    /**
     * SQL query to run. Anything JDBC will accept.
     */
    @Language("PostgreSQL")
    val query : String,
    /**
     * Name of the result class to generate for this query.
     * If the same name is shared among multiple SqlQuery annotations, they must all have the same outputs.
     * If fully qualified, an existing class will be used. Its primary constructor should accept the same named parameters.
     */
    val resultName : String = ""

)

@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY
)
annotation class SqlQueries(vararg val value : SqlQuery)

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY
)
@Retention(AnnotationRetention.SOURCE)
annotation class QueryContainer