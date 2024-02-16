package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import net.justmachinery.kdbgen.generation.GenerateElement

internal class GenerateCode(val generator : KdbGenerator) {
    fun ensureResultClass(name : ClassName, sources: List<GenerateElement>, construct : (TypeSpec.Builder)->Unit){
        val existing = fileBuilders.get(name.canonicalName)
        if(existing != null){
            existing.sources.addAll(sources)
        } else {
            generateFile(name, aggregating = false, sources = sources){
                val builder = TypeSpec.classBuilder(name)
                construct(builder)
                it.addType(builder.build())
            }
        }
    }

    private val fileBuilders = mutableMapOf<String, GenFileBuilder>()
    private class GenFileBuilder(
        val builder : FileSpec.Builder,
        val sources : MutableList<GenerateElement>,
        val aggregating : Boolean
    )


    private fun generateFile(
        className: ClassName,
        aggregating: Boolean,
        sources : List<GenerateElement>,
        create : (FileSpec.Builder)->Unit,
    ) = fileBuilders.getOrPut(className.canonicalName) {
        GenFileBuilder(
            builder = FileSpec.builder(className).also {
                it.addAnnotation(AnnotationSpec.builder(Suppress::class)
                    .also {
                        listOf(
                            "UNCHECKED_CAST",
                            "RemoveRedundantBackticks",
                            "RemoveRedundantQualifierName",
                            "UNUSED_PARAMETER",
                            "USELESS_CAST",
                        ).forEach { an ->
                            it.addMember(CodeBlock.of("\"$an\""))
                        }
                    }
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .build())
            },
            sources = sources.toMutableList(),
            aggregating = aggregating
        )
    }.also {
        create(it.builder)
    }

    fun generateCode(){
        try {
            generator.typeContext.enums.let {
                if (it.isNotEmpty()) {
                    it.values.forEach {
                        generateFile(it.className, aggregating = false, sources = it.sources){ builder ->
                            renderEnumType(builder, it)
                        }
                    }
                }
            }
            generator.typeContext.domains.let {
                if (it.isNotEmpty()) {
                    it.values.forEach {
                        generateFile(it.className, aggregating = false, sources = it.sources){ builder ->
                            renderDomainType(builder, it)
                        }
                    }
                }
            }

            generator.typeContext.composites.let {
                if (it.isNotEmpty()) {
                    it.values.forEach {
                        generateFile(it.className, aggregating = false, sources = it.sources){ builder ->
                            renderCompositeType(builder, it)
                        }
                    }
                }
            }

            for (query in generator.globalQueries) {
                val queryName = composeClassName(subpackage = "globalqueries", name = query.name)
                generateFile(queryName, aggregating = false, sources = listOf(query.element)){
                    GenerateQuery(
                        generateCode = this,
                        fileBuilder = it,
                        container = null,
                        containerName = null,
                        query = query
                    ).run()
                }
            }

            for (container in generator.containerQueries) {
                val parentPackage = container.parent.enclosingPackage()
                val containerName = if(parentPackage != null) {
                    ClassName.bestGuess("$parentPackage.${container.containerInterfaceName}")
                } else { 
                    composeClassName(subpackage = "querycontainers", name = container.containerInterfaceName) 
                }
                generateFile(containerName, aggregating = false, sources = listOf(container.parent)){
                    val queryContainerInterface = TypeSpec.interfaceBuilder(container.containerInterfaceName)
                    for (query in container.contents) {
                        GenerateQuery(
                            generateCode = this,
                            fileBuilder = it,
                            container = queryContainerInterface,
                            containerName = containerName,
                            query = query
                        ).run()
                    }
                    it.addType(queryContainerInterface.build())
                }
            }

            fileBuilders.entries.forEach { (name, builder) ->
                //In the common case where kdbgen is run on the main and test source sets, we don't want to double-generate
                //on things like enums &etc. So if the class already exists, don't generate it.
                if(!generator.context.gen.classExists(name)){
                    generator.context.gen.createCode(
                        sources = builder.sources,
                        aggregating = builder.aggregating,
                        spec = builder.builder.build()
                    )
                }
            }
        } catch(g : GeneratingException){
            generator.context.gen.logError(g.msg, g.element)
        }
    }
}

class GeneratingException(val msg : String, val element : GenerateElement) : RuntimeException()

internal fun composeClassName(basePackage: String? = generatedPackageName, subpackage: String? = null, name: String) = ClassName.bestGuess(listOfNotNull(basePackage, subpackage, name).joinToString("."))