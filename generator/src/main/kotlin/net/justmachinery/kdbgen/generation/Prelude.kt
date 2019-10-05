package net.justmachinery.kdbgen.generation

import com.squareup.kotlinpoet.asTypeName
import net.justmachinery.kdbgen.kapt.AnnotationContext
import net.justmachinery.kdbgen.kapt.SqlPrelude
import java.io.File
import java.util.*
import javax.lang.model.element.Element
import javax.lang.model.type.MirroredTypesException
import javax.tools.Diagnostic
import javax.tools.StandardLocation

internal class PreludeGenerator(private val context : AnnotationContext) {
    private val annotations = context.elements.prelude.flatMap { element ->
        element.getAnnotationsByType(SqlPrelude::class.java)!!.map {
            PreludeAnnotation(element, it)
        }

    }
    private val byClass = annotations
        .associateBy({ it.element.asType().asTypeName() }, { it })
    fun generate() : String {
        annotations.forEach {
            getAllDependencies(it, emptySet())
        }

        val sorted = LinkedHashSet<PreludeAnnotation>()
        annotations.forEach {
            addSorted(sorted, it)
        }

        val code = sorted.joinToString("\n\n;\n\n"){
            it.annotation.sql
        }
        if(sorted.isNotEmpty()){
            context.processingEnv.filer.createResource(StandardLocation.CLASS_OUTPUT, "", "prelude.sql").openWriter().use {
                it.write(code)
            }
            File(context.settings.outputDirectory).resolve("prelude.sql").writeText(code)
        }

        return code
    }

    private fun addSorted(sorted : LinkedHashSet<PreludeAnnotation>, annotation: PreludeAnnotation){
        if(!sorted.contains(annotation)){
            annotation.transitiveDependencies!!.forEach {
                addSorted(sorted, it)
            }
            sorted.add(annotation)
        }
    }

    private fun getAllDependencies(annotation : PreludeAnnotation, visited : Set<PreludeAnnotation>){
        annotation.transitiveDependencies?.let { return }
        if(visited.contains(annotation)){
            context.processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, "Circular dependency detected", annotation.element)
            throw IllegalStateException("Circular dependency detected")
        }
        val dependencies = try {
            annotation.annotation.dependencies.map { it.asTypeName() }
        } catch(mte : MirroredTypesException){
            val result = mte.typeMirrors.map { it.asTypeName() }
            result
        }.mapNotNull {
            byClass[it]
        }

        val deps = mutableSetOf<PreludeAnnotation>()
        dependencies.forEach {dependency ->
            deps.add(dependency)
            getAllDependencies(dependency, visited + annotation)
            deps.addAll(dependency.transitiveDependencies!!)
        }
        annotation.transitiveDependencies = deps

    }
}

internal class PreludeAnnotation(
    val element : Element,
    val annotation : SqlPrelude,
    var transitiveDependencies : Set<PreludeAnnotation>? = null
)