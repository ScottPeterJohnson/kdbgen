package net.justmachinery.kdbgen.kapt

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.asTypeName
import net.justmachinery.kdbgen.generation.*
import net.justmachinery.kdbgen.generation.sqlquery.generatedPackageName
import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypesException
import javax.tools.Diagnostic
import javax.tools.StandardLocation

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
        processingEnv.filer
        Generate().process(KaptGenerateContext(roundEnv, processingEnv))
        return true
    }
}

internal class KaptGenerateContext(val roundEnv: RoundEnvironment, val processingEnv : ProcessingEnvironment) : GenerateContext {
    override fun elementsAnnotatedWith(annotation: Class<out Annotation>): List<GenerateElement> {
        return roundEnv.getElementsAnnotatedWith(annotation).map { KaptGenerateElement(it) }.toList()
    }

    private val settings by lazy {
        val annotatedElements = AnnotatedElements(this)
        val annotations = annotatedElements.settings
        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
        when {
            annotations.size != 1 -> {
                processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Can't find @SqlGenerationSettings annotation")
                throw IllegalStateException()
            }
            kaptKotlinGeneratedDir == null -> {
                processingEnv.messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Can't find the target directory for generated Kotlin files."
                )
                throw IllegalStateException()
            }
            else -> {
                val annotation = (annotations.first().getAnnotations(SqlGenerationSettings::class.java).single() as KaptGenerateAnnotation).raw as SqlGenerationSettings
                (Settings(
                    databaseUrl = annotation.databaseUrl,
                    outputDirectory = annotation.outputDirectory.nullIfEmpty() ?: kaptKotlinGeneratedDir
                ))
            }
        }
    }
    override fun resolveSettings() = settings

    override fun classExists(name: String) = try {
        Class.forName(name)
        true
    } catch(t : ClassNotFoundException){
        false
    }


    override fun logError(error: String, at: GenerateElement?) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, error, at?.unwrap()?.element)
    }

    override fun createResource(
        packageName: String,
        fileName: String,
        extensionName: String,
        sources: List<GenerateElement>,
        aggregating: Boolean,
        content: String
    ) {
        processingEnv.filer.createResource(StandardLocation.CLASS_OUTPUT, packageName, "$fileName.$extensionName").openWriter().use {
            it.write(content)
        }
        File(resolveSettings().outputDirectory).resolve("$fileName.$extensionName").writeText(content)
    }

    private val prepareOutputDir by lazy {
        val outputDirectory = File(settings.outputDirectory)
        outputDirectory.resolve(generatedPackageName.replace('.', '/')).deleteRecursively()
    }

    override fun createCode(sources: List<GenerateElement>, aggregating: Boolean, spec: FileSpec) {
        prepareOutputDir

        //TODO: Unclear whether there's a way to use the Filer API and also generate Kotlin source files.
        //This precludes incremental annotation processing.
        /*generator.context.processingEnv.filer.createResource(
        StandardLocation.SOURCE_OUTPUT,
        generatedPackageName,
        "Queries.kt",
        *(generator.context.elements.all.toTypedArray())
    ).openWriter().use {
        fileBuilder.build().writeTo(it)
    }*/
        spec.writeTo(File(settings.outputDirectory))
    }
}

data class KaptGenerateElement(val element : Element) : GenerateElement {
    override fun enclosingPackage(): String? {
        return parentPackage(element)
    }
    private fun parentPackage(element: Element) : String? {
        if(element is PackageElement){
            return element.qualifiedName?.toString()?.nullIfEmpty()
        } else {
            val enclosing = element.enclosingElement
            return if(enclosing != null){
                parentPackage(enclosing)
            } else {
                null
            }
        }
    }
    override fun enclosingClass(): GenerateElement? {
        return element.enclosingElement?.let { KaptGenerateElement(it) }
    }

    override fun getAnnotations(annotation: Class<out Annotation>): List<GenerateAnnotation> {
        return element.getAnnotationsByType(annotation).map { KaptGenerateAnnotation(it) }
    }

    override fun simpleName() : String {
        return element.simpleName.toString()
    }
    override fun qualifiedName(): String? {
        return (element.asType().asTypeName().toString()).nullIfEmpty()
    }
}
class KaptGenerateAnnotation(val raw : Any) : GenerateAnnotation {
    override fun parseSqlPrelude(): SqlPreludeData {
        val data =  raw as SqlPrelude
        return SqlPreludeData(data.sql, try {
            data.dependencies.map { it.qualifiedName!! }
        } catch(mte : MirroredTypesException){
            val result = mte.typeMirrors.map { it.asTypeName().toString() }
            result!!
        })
    }

    override fun parseSqlQuery(): SqlQuery {
        return raw as SqlQuery
    }

}
private fun GenerateElement.unwrap() = this as KaptGenerateElement


internal fun String.nullIfEmpty() = if(this.isEmpty()) null else this
