package net.justmachinery.kdbgen.kapt

import com.google.auto.service.AutoService
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.runGeneration
import net.justmachinery.kdbgen.generation.sqlquery.SqlQueryWrapperGenerator
import java.sql.SQLException
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("net.justmachinery.kdbgen.kapt.SqlQuery", "net.justmachinery.kdbgen.kapt.SqlQueries")
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME)
class SqlQueryProcessor : AbstractProcessorBase() {
    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val elements = roundEnv.getElementsAnnotatedWith(SqlQuery::class.java).union(roundEnv.getElementsAnnotatedWith(SqlQueries::class.java))
        if(elements.isNotEmpty()){
            withGenerationSettings(roundEnv){ settings ->
                SqlQueryWrapperGenerator(settings).use { generator ->
                    for(element in elements){
                        for(annotation in element.getAnnotationsByType(SqlQuery::class.java)){
                            try {
                                generator.processStatement(annotation.name, annotation.query, annotation.resultName)
                            } catch(e : SQLException){
                                processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Could not process query: $e", element)
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("net.justmachinery.kdbgen.kapt.GeneratePostgresInterface")
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME)
class GeneratePostgresProcessor : AbstractProcessorBase() {
    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if(roundEnv.getElementsAnnotatedWith(GeneratePostgresInterface::class.java).isNotEmpty()){
            withGenerationSettings(roundEnv) {settings ->
                runGeneration(settings)
            }
        }
        return true
    }
}

abstract class AbstractProcessorBase : AbstractProcessor() {
    protected fun withGenerationSettings(roundEnv : RoundEnvironment, cb : (Settings)->Unit){
        val annotations = roundEnv.getElementsAnnotatedWith(SqlGenerationSettings::class.java)
        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
        when {
            annotations.size != 1 -> processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find @SqlGenerationSettings annotation")
            kaptKotlinGeneratedDir == null -> processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find the target directory for generated Kotlin files.")
            else -> {
                val annotation = annotations.first().getAnnotation(SqlGenerationSettings::class.java)
                cb(Settings(
                    databaseUrl = annotation.databaseUrl,
                    outputDirectory = annotation.outputDirectory.nullIfEmpty() ?: kaptKotlinGeneratedDir,
                    dslOutputDirectory = annotation.dslOutputDirectory.nullIfEmpty() ?: kaptKotlinGeneratedDir,
                    useCommonTypes = annotation.useCommonTypes,
                    enumPackage = annotation.enumPackage,
                    dataPackage = annotation.dataPackage,
                    dataAnnotation = annotation.dataAnnotation.toList(),
                    mutableData = annotation.mutableData
                ))
            }
        }
    }

}

private fun String.nullIfEmpty() = if(this.isEmpty()) null else this