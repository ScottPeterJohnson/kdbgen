package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import java.io.File
import javax.lang.model.element.Element
import javax.tools.Diagnostic

internal class GenerateCode(val generator : KdbGenerator) {
    private val globallyOutputtedClasses = mutableSetOf<ClassName>()
    fun ensureGlobal(name : ClassName, construct : (TypeSpec.Builder)->Unit){
        if(globallyOutputtedClasses.add(name)){
            val builder = TypeSpec.classBuilder(name)
            construct(builder)
            fileBuilderFor("SharedResultClasses").addType(builder.build())
        }
    }

    private val fileBuilders = mutableMapOf<String, FileSpec.Builder>()
    private fun fileBuilderFor(name : String) = fileBuilders.getOrPut(name) {
        FileSpec.builder(generatedPackageName, name).also {
            it.addAnnotation(AnnotationSpec.builder(Suppress::class)
                .also {
                    listOf(
                        "UNCHECKED_CAST",
                        "RemoveRedundantBackticks",
                        "RemoveRedundantQualifierName"
                    ).forEach { an ->
                        it.addMember(CodeBlock.of("\"$an\""))
                    }
                }
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build())
        }
    }

    fun generateCode(){
        try {
            generator.typeContext.enums.let {
                if (it.isNotEmpty()) {
                    renderEnumTypes(fileBuilderFor("EnumTypes"), it.values.toList())
                }
            }
            generator.typeContext.domains.let {
                if (it.isNotEmpty()) {
                    renderDomainTypes(fileBuilderFor("DomainTypes"), it.values.toList())
                }
            }

            generator.typeContext.composites.let {
                if (it.isNotEmpty()) {
                    renderCompositeTypes(fileBuilderFor("CompositeTypes"), it.values.toList())
                }
            }

            for (query in generator.globalQueries) {
                GenerateQuery(
                    generateCode = this,
                    fileBuilder = fileBuilderFor("GlobalQueries"),
                    container = null,
                    containerName = null,
                    query = query
                )
            }

            for (container in generator.containerQueries) {
                val queryContainerInterface = TypeSpec.interfaceBuilder(container.containerInterfaceName)
                val builder = fileBuilderFor(container.containerInterfaceName)
                for (query in container.contents) {
                    GenerateQuery(
                        generateCode = this,
                        fileBuilder = builder,
                        container = queryContainerInterface,
                        containerName = container.containerInterfaceName,
                        query = query
                    )
                }
                builder.addType(queryContainerInterface.build())
            }

            val outputDirectory = File(generator.context.settings.outputDirectory)
            outputDirectory.resolve(generatedPackageName.replace('.', '/')).deleteRecursively()
            fileBuilders.values.forEach { builder ->
                builder.build().writeTo(outputDirectory)
            }
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
        } catch(g : GeneratingException){
            generator.context.processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, g.msg, g.element)
        }
    }


}

class GeneratingException(val msg : String, val element : Element) : RuntimeException()