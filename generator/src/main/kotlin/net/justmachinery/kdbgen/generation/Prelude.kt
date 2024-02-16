package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.kapt.SqlPrelude

internal class PreludeGenerator(private val context : AnnotationContext) {
    private val annotations = context.elements.prelude.flatMap { element ->
        element.getAnnotations(SqlPrelude::class.java).map {
            PreludeAnnotation(element, it.parseSqlPrelude())
        }

    }
    private val byClass = annotations
        .associateBy({ it.element.qualifiedName() }, { it })
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
            context.gen.createResource("", "prelude", "sql", sorted.map { it.element }, aggregating = true, code)
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
            context.gen.logError("Circular dependency detected", annotation.element)
            throw IllegalStateException("Circular dependency detected")
        }
        val dependencies = annotation.annotation.dependencies.mapNotNull {
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
    val element : GenerateElement,
    val annotation : SqlPreludeData,
    var transitiveDependencies : Set<PreludeAnnotation>? = null
)