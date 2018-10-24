package net.justmachinery.kdbgen.kapt

import com.google.auto.service.AutoService
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.runGeneration
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("net.justmachinery.kdbgen.kapt.GeneratePostgresInterface")
@SupportedOptions(GeneratePostgresProcessor.KAPT_KOTLIN_GENERATED_OPTION_NAME)
class GeneratePostgresProcessor : AbstractProcessor() {
    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
    }
    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val elements = roundEnv.getElementsAnnotatedWith(GeneratePostgresInterface::class.java)
        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] ?: run {
            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find the target directory for generated Kotlin files.")
            return false
        }
        for(element in elements){
            val annotation = element.getAnnotation(GeneratePostgresInterface::class.java)
            runGeneration(Settings(
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
        return true
    }
}

private fun String.nullIfEmpty() = if(this.isEmpty()) null else this