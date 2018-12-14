package net.justmachinery.kdbgen.kapt

import com.google.auto.service.AutoService
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.sqlquery.SqlQueryWrapperGenerator
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("net.justmachinery.kdbgen.kapt.SqlQuery", "net.justmachinery.kdbgen.kapt.SqlQueries")
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME)
class SqlQueryProcessor : AbstractProcessor() {
    override fun process(_1: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val annotatedElements = roundEnv.getElementsAnnotatedWith(SqlQuery::class.java).union(roundEnv.getElementsAnnotatedWith(SqlQueries::class.java))
        if(annotatedElements.isNotEmpty()){
            withGenerationSettings(roundEnv){ settings ->
                try {
                    SqlQueryWrapperGenerator(processingEnv.messager, settings)
                } catch(t : Throwable){
                    processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Could not run kdbgen: $t")
                    null
                }?.use { generator ->
                    val elementsByContainer = annotatedElements.groupBy {
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

    private fun withGenerationSettings(roundEnv : RoundEnvironment, cb : (Settings)->Unit){
        val annotations = roundEnv.getElementsAnnotatedWith(SqlGenerationSettings::class.java)
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


private fun String.nullIfEmpty() = if(this.isEmpty()) null else this