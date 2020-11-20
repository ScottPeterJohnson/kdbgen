package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import net.justmachinery.kdbgen.ConnectionProvider
import net.justmachinery.kdbgen.generation.ConvertFromSqlContext
import net.justmachinery.kdbgen.generation.ConvertToSqlContext
import java.io.File
import javax.lang.model.element.Element
import javax.tools.Diagnostic

internal class GenerateCode(private val generator : SqlQueryWrapperGenerator) {

    val globallyOutputtedClasses = mutableSetOf<ClassName>()

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
        generator.typeContext.enums.let {
            if(it.isNotEmpty()){
                renderEnumTypes(fileBuilderFor("EnumTypes"), it.values.toList())
            }
        }
        generator.typeContext.domains.let {
            if(it.isNotEmpty()){
                renderDomainTypes(fileBuilderFor("DomainTypes"), it.values.toList())
            }
        }

        generator.typeContext.composites.let {
            if(it.isNotEmpty()){
                renderCompositeTypes(fileBuilderFor("CompositeTypes"), it.values.toList())
            }
        }

        for(query in generator.globalQueries){
            GenerateQuery(fileBuilderFor("GlobalQueries"), null, null, query)
        }

        for(container in generator.containerQueries){
            val queryContainerInterface = TypeSpec.interfaceBuilder(container.containerInterfaceName)
            val builder = fileBuilderFor(container.containerInterfaceName)
            for(query in container.contents){
                GenerateQuery(
                    fileBuilder = builder,
                    container = queryContainerInterface,
                    containerName = container.containerInterfaceName,
                    query = query
                )
            }
            builder.addType(queryContainerInterface.build())
        }

        val outputDirectory = File(generator.context.settings.outputDirectory)
        outputDirectory.resolve(generatedPackageName.replace('.','/')).deleteRecursively()
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
    }

    internal sealed class ResultSetOutput {
        object None : ResultSetOutput()
        class DirectType(val name : TypeName, val wrapper : ClassName) : ResultSetOutput()
        class ExistingConstructedType(val name : TypeName, val wrapper : ClassName) : ResultSetOutput()
        class Wrapper(val wrapper : ClassName) : ResultSetOutput()

        fun extractName() : TypeName {
            return when(this){
                is Wrapper -> wrapper
                is DirectType -> name
                is ExistingConstructedType -> name
                else -> throw IllegalStateException()
            }
        }
        fun extractWrapper() : ClassName {
            return when(this){
                is Wrapper -> wrapper
                is DirectType -> wrapper
                is ExistingConstructedType -> wrapper
                else -> throw IllegalStateException()
            }
        }
    }

    internal inner class GenerateQuery(
        val fileBuilder : FileSpec.Builder,
        val container : TypeSpec.Builder?,
        val containerName : String?,
        val query : SqlQueryData
    ) {
        private val isMultiOuterResult = query.resultSets.count { it.columns.isNotEmpty() } > 1
        private val isOuterResultNamed = query.outerResultName != null
        private val generateNamedOuterWrapper = isOuterResultNamed && query.outerResultName?.packageName == generatedPackageName
        private val outerUseExistingType = isOuterResultNamed && query.outerResultName?.packageName != generatedPackageName

        private val multiOuterWrapperName = when {
            generateNamedOuterWrapper -> query.outerResultName!!
            else -> {
                val autoGeneratedName = "${query.name.capitalize()}Result"
                when {
                    container != null -> ClassName(generatedPackageName, containerName!!, autoGeneratedName)
                    else -> ClassName(generatedPackageName, autoGeneratedName)
                }
            }
        }

        init {
            try {
                val resultClasses = generateResultClasses()
                generateMultiResultWrapper(resultClasses)
                generateFunction(resultClasses)
            } catch(g : GeneratingException){
                generator.context.processingEnv.messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    g.msg,
                    g.element
                )
            }
        }

        private fun generateResultClasses() : Map<ResultSetData, ResultSetOutput> {
            return query.resultSets.withIndex().associateBy(
                { it.value },
                { (index, resultSet) ->
                    val useOuterName = isOuterResultNamed && !isMultiOuterResult

                    val useInnerName = resultSet.innerResultName != null
                    val innerUseExistingType = useInnerName && resultSet.innerResultName?.packageName != generatedPackageName

                    val shouldGenerateNamedResultWrapper = (useOuterName && generateNamedOuterWrapper) || (useInnerName && !innerUseExistingType)

                    val returnsAnything = resultSet.columns.isNotEmpty()
                    //If only one column is returned, we can directly return its type UNLESS the user explicitly names a type
                    val returnsDirectType = resultSet.columns.size == 1 && !useOuterName && !useInnerName
                    val returnsUserType = (useOuterName && outerUseExistingType) || (useInnerName && innerUseExistingType)

                    //We always define a result class so that we can get its types for conversion, even if the query returns directly,
                    //or returns an explicitly named class.
                    val resultWrapperClassName =
                        when {
                            shouldGenerateNamedResultWrapper -> if(useOuterName) query.outerResultName!! else resultSet.innerResultName!!
                            else -> {
                                val autoGeneratedName = "${query.name.capitalize()}Result$index"
                                when {
                                    container != null -> ClassName(
                                        generatedPackageName,
                                        containerName!!,
                                        autoGeneratedName
                                    )
                                    else -> ClassName(generatedPackageName, autoGeneratedName)
                                }
                            }
                        }
                    if (returnsAnything) {
                        //Skip if this a shared global output we've already generated
                        if (!shouldGenerateNamedResultWrapper || !globallyOutputtedClasses.contains(
                                resultWrapperClassName
                            )
                        ) {
                            val resultClassBuilder = TypeSpec.classBuilder(resultWrapperClassName)
                                .addModifiers(KModifier.DATA)
                            if (returnsDirectType || returnsUserType) {
                                resultClassBuilder.addModifiers(KModifier.PRIVATE)
                            }

                            val primaryConstructor = FunSpec.constructorBuilder()
                            for (column in resultSet.columns) {
                                primaryConstructor.addParameter(column.columnName, column.type.asTypeName())
                                resultClassBuilder.addProperty(
                                    PropertySpec.builder(
                                        column.columnName,
                                        column.type.asTypeName()
                                    ).initializer(column.columnName).build()
                                )
                            }
                            resultClassBuilder.primaryConstructor(primaryConstructor.build())

                            if (shouldGenerateNamedResultWrapper) {
                                fileBuilder.addType(resultClassBuilder.build())
                                globallyOutputtedClasses.add(resultWrapperClassName)
                            } else {
                                addTypeInContainer(resultClassBuilder.build())
                            }
                        }
                        when {
                            returnsDirectType -> ResultSetOutput.DirectType(
                                resultSet.columns.single().type.asTypeName(),
                                resultWrapperClassName
                            )
                            returnsUserType -> ResultSetOutput.ExistingConstructedType(
                                if(useOuterName) query.outerResultName!! else resultSet.innerResultName!!,
                                resultWrapperClassName
                            )
                            else -> ResultSetOutput.Wrapper(resultWrapperClassName)
                        }
                    } else {
                        ResultSetOutput.None
                    }
            })
        }

        private fun generateMultiResultWrapper(resultSetOutputs : Map<ResultSetData, ResultSetOutput>) {
            if(isMultiOuterResult && !outerUseExistingType){
                val resultClassBuilder = TypeSpec.classBuilder(multiOuterWrapperName)
                    .addModifiers(KModifier.DATA)

                val primaryConstructor = FunSpec.constructorBuilder()
                for ((index, resultSet) in query.resultSets.withIndex()) {
                    val result = resultSetOutputs.getValue(resultSet)
                    if(result !is ResultSetOutput.None){
                        val resultSetRows = List::class.asClassName().parameterizedBy(result.extractName())
                        val name = "resultSet${index+1}"
                        primaryConstructor.addParameter(name, resultSetRows)
                        resultClassBuilder.addProperty(
                            PropertySpec.builder(
                                name,
                                resultSetRows
                            ).initializer(name).build()
                        )
                    }
                }
                resultClassBuilder.primaryConstructor(primaryConstructor.build())

                if (generateNamedOuterWrapper) {
                    fileBuilder.addType(resultClassBuilder.build())
                    globallyOutputtedClasses.add(multiOuterWrapperName)
                } else {
                    addTypeInContainer(resultClassBuilder.build())
                }
            }
        }

        private fun generateFunction(resultSetOutputs : Map<ResultSetData, ResultSetOutput>) {
            val function = FunSpec.builder(query.name)
            function.receiver(ConnectionProvider::class)

            val namedParameters = query.inputs.groupBy { it.parameterName }

            //Sanity check named parameters
            for(values in namedParameters.values){
                if(!values.all { it == values.first() }) {
                    throw GeneratingException(
                        "In query ${query.name}, types of named parameter ${values.first().parameterName} in multiple locations do not match: $values",
                        query.element
                    )
                }
            }

            for((name, params) in namedParameters){
                function.addParameter(name, params.first().type.asTypeName())
            }

            function.addStatement("val connection = this.getConnection()")
            function.beginControlFlow("connection.prepareStatement(%S).use", query.query)
            function.addStatement("prepared ->")
            for((index, param) in query.inputs.withIndex()){
                function.addStatement("prepared.setObject(${index+1}, ${param.type.convertToSql(ConvertToSqlContext(param.parameterName))}, ${param.sqlTypeCode})")
            }

            function.addStatement("prepared.execute()")

            if(resultSetOutputs.values.any { it !is ResultSetOutput.None }){
                val multiResultName = when {
                    outerUseExistingType -> query.outerResultName!!
                    else -> multiOuterWrapperName
                }
                when {
                    resultSetOutputs.size == 1 -> {
                        val singleResultSet = resultSetOutputs.values.single().extractName()
                        function.returns(List::class.asClassName().parameterizedBy(singleResultSet))
                    }
                    else -> function.returns(multiResultName)
                }

                val resultSubs = mutableListOf<String>()
                for((index, resultSet) in query.resultSets.withIndex()){
                    if(index > 0){
                        function.addStatement("prepared.moreResults")
                    }

                    function.addStatement("var rs$index = prepared.resultSet")
                    function.beginControlFlow("while(rs$index == null)")
                        function.beginControlFlow("if(!prepared.moreResults && prepared.updateCount == -1)")
                            function.addStatement("throw IllegalStateException(\"Invalid returned result set structure\")")
                        function.endControlFlow()
                        function.addStatement("rs$index = prepared.resultSet")
                    function.endControlFlow()

                        val returns = resultSetOutputs.getValue(resultSet)
                        if(returns !is ResultSetOutput.None){
                            function.addStatement("val results$index = mutableListOf<%T>()", returns.extractName())
                            resultSubs.add("results$index")
                            function.beginControlFlow("while(rs$index.next())")

                            for((columnIndex, column) in resultSet.columns.withIndex()){
                                function.addStatement(
                                    "val out$columnIndex = rs$index.getObject(\"${column.columnName}\"${if (column.type.isBase) "" else ", ${column.type.sqlRepr}::class.java"}).let { result -> ${
                                        column.type.convertFromSql(
                                            ConvertFromSqlContext("result")
                                        )
                                    } }"
                                )
                            }

                            if(returns is ResultSetOutput.DirectType){
                                function.addStatement("results$index.add(out0)")
                            } else {
                                function.addStatement("results$index.add(%T(${resultSet.columns.withIndex().joinToString(", ") { (index, it) -> "`${it.columnName}` = out$index" }}))", returns.extractName())
                            }

                            function.endControlFlow()

                            if(!isMultiOuterResult){
                                function.addStatement("return results$index")
                            }
                        }
                }
                if(isMultiOuterResult){
                    function.addStatement("return %T(${resultSubs.joinToString(",")})", multiResultName)
                }
            }

            function.endControlFlow()

            addFunctionInContainer(function.build())
        }


        private fun addTypeInContainer(it : TypeSpec){
            if(container != null){
                container.addType(it)
            } else {
                fileBuilder.addType(it)
            }
        }
        private fun addFunctionInContainer(it : FunSpec){
            if(container != null){
                container.addFunction(it)
            } else {
                fileBuilder.addFunction(it)
            }
        }
    }
}

class GeneratingException(val msg : String, val element : Element) : RuntimeException()