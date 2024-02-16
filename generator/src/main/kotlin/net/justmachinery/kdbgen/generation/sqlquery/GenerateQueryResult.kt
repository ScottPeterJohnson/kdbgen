package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

internal sealed class ResultSetOutput {
    object None : ResultSetOutput()

    abstract class HasResultSetData : ResultSetOutput() {
        abstract val resultSet : ResultSetData
    }
    class DirectType(val name : TypeName, override val resultSet : ResultSetData) : HasResultSetData()
    class Wrapper(val wrapper : ClassName, override val resultSet : ResultSetData) : HasResultSetData()
    class MultiResultSets(val wrapper : ClassName, val parts : List<ResultSetOutput>) : ResultSetOutput()

    fun extractName() : TypeName {
        return when(this){
            is Wrapper -> wrapper
            is DirectType -> name
            is MultiResultSets -> wrapper
            else -> Unit::class.asTypeName()
        }
    }
}

internal class GenerateQueryResult(
    private val generate : GenerateQuery
) {
    private val query get() = generate.query
    private val isMultiOuterResult = query.resultSets.count { it.columns.isNotEmpty() } > 1


    fun generate() : ResultSetOutput {
        val output = generateResultClasses()
        val multiOutput = generateMultiResultWrapper(output)
        return if(multiOutput != null){
            ResultSetOutput.MultiResultSets(multiOutput, output)
        } else {
            output.single()
        }
    }

    sealed class ResultSetName {
        data class GeneratedName(val simpleName : String) : ResultSetName()
        data class ExistingName(val className: ClassName) : ResultSetName()
        object Autogenerate : ResultSetName()
    }
    private fun generateResultClasses() : List<ResultSetOutput> {
        return query.resultSets.withIndex().map { (index, resultSet) ->

            val resultName: String? = when {
                query.outerResultName != null && !isMultiOuterResult -> query.outerResultName!!
                resultSet.innerResultName != null -> resultSet.innerResultName
                else -> {
                    null
                }
            }
            val resultWrapperName = if(resultName != null && resultName.isNotBlank()){
                if(resultName.contains(".")){
                    ResultSetName.ExistingName(ClassName.bestGuess(resultName))
                } else {
                    ResultSetName.GeneratedName(resultName)
                }
            } else {
                ResultSetName.Autogenerate
            }

            when {
                resultSet.columns.isEmpty() -> ResultSetOutput.None
                resultSet.columns.size == 1 && resultWrapperName == ResultSetName.Autogenerate -> ResultSetOutput.DirectType(
                    resultSet.columns.single().type.asTypeName(),
                    resultSet
                )
                else -> {
                    fun generateOutput(resultClassBuilder: TypeSpec.Builder) {
                        resultClassBuilder.addModifiers(KModifier.DATA)
                        val primaryConstructor = FunSpec.constructorBuilder()
                        for (column in resultSet.columns) {
                            primaryConstructor.addPropertyParameter(
                                clazz = resultClassBuilder,
                                name = column.columnName,
                                type = column.type.asTypeName(),
                                modifier = KModifier.PUBLIC
                            )
                        }
                        resultClassBuilder.primaryConstructor(primaryConstructor.build())
                    }

                    ResultSetOutput.Wrapper(
                        when (resultWrapperName) {
                            is ResultSetName.ExistingName -> {
                                resultWrapperName.className
                            }
                            is ResultSetName.Autogenerate -> {
                                generate.generateClass(
                                    "Result${if (isMultiOuterResult) index.toString() else ""}"
                                ){ it, name -> generateOutput(it) }
                            }
                            is ResultSetName.GeneratedName -> {
                                val fullName = composeClassName(
                                    basePackage = query.element.enclosingPackage(),
                                    name = resultWrapperName.simpleName
                                )
                                generate.generateCode.ensureResultClass(fullName, sources = listOf(query.element)){ generateOutput(it) }
                                fullName
                            }
                        },
                        resultSet
                    )
                }
            }
        }
    }

    private fun generateMultiResultWrapper(resultSetOutputs : List<ResultSetOutput>) : ClassName? {
        return if(isMultiOuterResult){
            fun generateOutput(resultClassBuilder: TypeSpec.Builder) {
                resultClassBuilder.addModifiers(KModifier.DATA)
                val primaryConstructor = FunSpec.constructorBuilder()
                for ((index, result) in resultSetOutputs.withIndex()) {
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
            }

            val outerName = query.outerResultName
            when {
                outerName != null -> {
                    if(outerName.contains(".")){
                        ClassName.bestGuess(outerName)
                    } else {
                        val fullOuterName = composeClassName(basePackage = query.element.enclosingPackage(), name = outerName)
                        generate.generateCode.ensureResultClass(fullOuterName, sources = listOf(query.element), ::generateOutput)
                        fullOuterName
                    }
                }
                else -> {
                    generate.generateClass("Result", { it, _ -> generateOutput(it) })
                }
            }
        } else {
            null
        }
    }
}