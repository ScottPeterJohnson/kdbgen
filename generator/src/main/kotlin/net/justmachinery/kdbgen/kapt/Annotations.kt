package net.justmachinery.kdbgen.kapt

import net.justmachinery.kdbgen.generation.PreludeGenerator
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.sqlquery.SqlQueryWrapperGenerator
import java.io.PrintWriter
import java.io.StringWriter
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@SupportedAnnotationTypes(
    "net.justmachinery.kdbgen.kapt.SqlGenerationSettings",
    "net.justmachinery.kdbgen.kapt.SqlQuery",
    "net.justmachinery.kdbgen.kapt.SqlQueries",
    "net.justmachinery.kdbgen.kapt.QueryContainer",
    "net.justmachinery.kdbgen.kapt.SqlPrelude",
    "net.justmachinery.kdbgen.kapt.SqlPreludes"
)
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME)
class SqlQueryProcessor : AbstractProcessor() {
    override fun process(_1: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val annotatedElements = AnnotatedElements(roundEnv)
        processingEnv.filer
        if(annotatedElements.queries.isNotEmpty()){
            withGenerationSettings(annotatedElements){ settings ->
                val context = AnnotationContext(
                    processingEnv = processingEnv,
                    settings = settings,
                    elements = annotatedElements
                )
                try {
                    val prelude = PreludeGenerator(context).generate()
                    SqlQueryWrapperGenerator(context, prelude).use { generator ->
                        val elementsByContainer = annotatedElements.queries.groupBy {
                            it.queryContainerParent()
                        }
                        for((container, elements) in elementsByContainer.entries){
                            if(container == null){
                                for(element in elements){
                                    for(annotation in element.getAnnotationsByType(SqlQuery::class.java)){
                                        generator.processGlobalStatement(annotation, element)
                                    }
                                }
                            } else {
                                val klazz = container as TypeElement
                                val queryInterfaceName = klazz.simpleName.toString() + "Queries"
                                generator
                                    .processQueryContainer(
                                        queryInterfaceName,
                                        elements.flatMap { el ->
                                            el.getAnnotationsByType(SqlQuery::class.java).map { Pair(it, el) }.toList()
                                        }
                                    )
                            }
                        }
                    }
                } catch(t : Throwable){
                    val stackString = StringWriter().also {
                        t.printStackTrace(PrintWriter(it))
                    }.toString()
                    processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Could not run kdbgen: $t\n$stackString")
                }
            }
        }
        return true
    }

    private fun Element.queryContainerParent() : Element? {
        if(this.enclosingElement != null){
            return if(this.enclosingElement.getAnnotation(QueryContainer::class.java) != null) this.enclosingElement
                else this.enclosingElement.queryContainerParent()
        }
        return null
    }

    private fun withGenerationSettings(annotatedElements: AnnotatedElements, cb : (Settings)->Unit){
        val annotations = annotatedElements.settings
        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
        when {
            annotations.size != 1 -> processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find @SqlGenerationSettings annotation")
            kaptKotlinGeneratedDir == null -> processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find the target directory for generated Kotlin files.")
            else -> {
                val annotation = annotations.first().getAnnotation(SqlGenerationSettings::class.java)
                cb(Settings(
                    databaseUrl = annotation.databaseUrl,
                    outputDirectory = annotation.outputDirectory.nullIfEmpty() ?: kaptKotlinGeneratedDir
                ))
            }
        }
    }
}

internal class AnnotationContext(
    val processingEnv: ProcessingEnvironment,
    val settings: Settings,
    val elements : AnnotatedElements
)

internal class AnnotatedElements(
    roundEnv: RoundEnvironment
){
    val queries = roundEnv.getElementsAnnotatedWith(SqlQuery::class.java).union(roundEnv.getElementsAnnotatedWith(SqlQueries::class.java))
    val prelude = roundEnv.getElementsAnnotatedWith(SqlPrelude::class.java).union(roundEnv.getElementsAnnotatedWith(SqlPreludes::class.java))
    val settings by lazy { roundEnv.getElementsAnnotatedWith(SqlGenerationSettings::class.java) }
    val containers by lazy { roundEnv.getElementsAnnotatedWith(QueryContainer::class.java) }

    val all by lazy { queries + settings + containers + prelude }
}

private fun String.nullIfEmpty() = if(this.isEmpty()) null else this