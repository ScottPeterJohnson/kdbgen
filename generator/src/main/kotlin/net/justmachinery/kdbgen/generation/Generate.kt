package net.justmachinery.kdbgen.generation

import com.squareup.kotlinpoet.FileSpec
import net.justmachinery.kdbgen.generation.sqlquery.KdbGenerator
import net.justmachinery.kdbgen.kapt.*
import java.io.PrintWriter
import java.io.StringWriter

internal interface GenerateContext {
    fun elementsAnnotatedWith(annotation : Class<out Annotation>) : List<GenerateElement>
    fun classExists(name : String) : Boolean
    fun resolveSettings() : Settings
    fun logError(error : String, at : GenerateElement? = null)
    fun createResource(
        packageName: String,
        fileName: String,
        extensionName: String,
        sources: List<GenerateElement>,
        aggregating: Boolean,
        content: String
    )
    fun createCode(sources: List<GenerateElement>, aggregating: Boolean, spec: FileSpec)
}

interface GenerateElement {
    fun enclosingPackage() : String?
    fun enclosingClass() : GenerateElement?
    fun getAnnotations(annotation: Class<out Annotation>) : List<GenerateAnnotation>
    fun simpleName() : String
    fun qualifiedName() : String?
}
interface GenerateAnnotation {
    fun parseSqlPrelude() : SqlPreludeData
    fun parseSqlQuery() : SqlQuery
}
data class SqlPreludeData(val sql : String, val dependencies : List<String>)

internal class Generate {
    fun process(gen: GenerateContext): Boolean {
        val annotatedElements = AnnotatedElements(gen)
        if(annotatedElements.queries.isNotEmpty()){
            val settings = gen.resolveSettings()
            val context = AnnotationContext(
                gen = gen,
                settings = settings,
                elements = annotatedElements
            )
            try {
                val prelude = PreludeGenerator(context).generate()
                KdbGenerator(context, prelude).use { generator ->
                    val elementsByContainer = annotatedElements.queries.groupBy {
                        it.queryContainerParent()
                    }
                    for((container, elements) in elementsByContainer.entries){
                        if(container == null){
                            for(element in elements){
                                for(annotation in element.getAnnotations(SqlQuery::class.java)){
                                    val qe = KdbGenerator.QueryElement(annotation.parseSqlQuery(), element)
                                    generator.processGlobalStatement(qe)
                                }
                            }
                        } else {
                            val queryInterfaceName = container.simpleName() + "Queries"
                            generator
                                .processQueryContainer(
                                    parent = container,
                                    containerName = queryInterfaceName,
                                    queries = elements.flatMap { el ->
                                        el.getAnnotations(SqlQuery::class.java).map { KdbGenerator.QueryElement(it.parseSqlQuery(), el) }.toList()
                                    }
                                )
                        }
                    }
                }
            } catch(t : Throwable){
                val stackString = StringWriter().also {
                    t.printStackTrace(PrintWriter(it))
                }.toString()
                gen.logError("Could not run kdbgen: $t\n$stackString")
            }

        }
        return true
    }

    private fun GenerateElement.queryContainerParent() : GenerateElement? {
        if(this.enclosingClass() != null){
            return if(this.enclosingClass()!!.getAnnotations(QueryContainer::class.java).isNotEmpty()) this.enclosingClass()
            else this.enclosingClass()!!.queryContainerParent()
        }
        return null
    }
}

internal class AnnotationContext(
    val gen: GenerateContext,
    val settings: Settings,
    val elements : AnnotatedElements
)

internal class AnnotatedElements(
    context : GenerateContext
){
    val queries = context.elementsAnnotatedWith(SqlQuery::class.java).union(context.elementsAnnotatedWith(
        SqlQueries::class.java))
    val prelude = context.elementsAnnotatedWith(SqlPrelude::class.java).union(context.elementsAnnotatedWith(
        SqlPreludes::class.java))
    val settings by lazy { context.elementsAnnotatedWith(SqlGenerationSettings::class.java) }
    val containers by lazy { context.elementsAnnotatedWith(QueryContainer::class.java) }

    val all by lazy { queries + settings + containers + prelude }
}