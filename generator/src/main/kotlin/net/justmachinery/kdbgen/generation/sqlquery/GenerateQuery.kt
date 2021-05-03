package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import net.justmachinery.kdbgen.ConnectionProvider
import net.justmachinery.kdbgen.generation.ConvertFromSqlContext
import net.justmachinery.kdbgen.generation.ConvertToSqlContext
import java.lang.IllegalStateException
import java.sql.PreparedStatement
import javax.tools.Diagnostic

internal class GenerateQuery(
    val generateCode: GenerateCode,
    val fileBuilder : FileSpec.Builder,
    val container : TypeSpec.Builder?,
    val containerName : String?,
    val query : SqlQueryData
) {
    fun generateClass(autogenName : String, cb: (TypeSpec.Builder)->Unit) : ClassName {
        val finalName = query.name.capitalize() + autogenName
        val className = when {
            container != null -> ClassName(generatedPackageName, containerName!!, finalName)
            else -> ClassName(generatedPackageName, finalName)
        }
        val builder = TypeSpec.classBuilder(className)
        cb(builder)
        if(container != null){
            container.addType(builder.build())
        } else {
            fileBuilder.addTypeIfNotExists(builder.build())
        }
        return className
    }



    fun run(){
        try {
            val resultClasses = GenerateQueryResult(this).generate()
            val queryClass = generateQueryClass(resultClasses)
            generateQueryGetter(queryClass)

        } catch(g : GeneratingException){
            generateCode.generator.context.processingEnv.messager.printMessage(
                Diagnostic.Kind.ERROR,
                g.msg,
                g.element
            )
        }
    }

    private fun generateQueryGetter(queryClass : ClassName){
        val property = PropertySpec.builder(query.name, queryClass)
            .receiver(ConnectionProvider::class)
            .getter(FunSpec.getterBuilder()
                .addStatement("return %T(this)", queryClass)
                .build())
            .build()

        if(container != null){
            container.addProperty(property)
        } else {
            fileBuilder.addProperty(property)
        }
    }


    private fun generateQueryClass(output : ResultSetOutput) : ClassName {
        return generateClass("Query"){
            val primaryConstructor = FunSpec.constructorBuilder()
                .addPropertyParameter(it, "connectionProvider", ConnectionProvider::class.asTypeName())
            it.primaryConstructor(primaryConstructor.build())
            it.addFunction(generateInvokeFunction(output))
            it.addFunction(generatePrepareFunction())
            it.addFunction(generateExecuteFunction())
            it.addFunction(generateExtractFunction(output))

            if(hasArrayInputs){
                it.addProperty(PropertySpec.builder(
                    name = "__arrays",
                    type = ArrayList::class.asTypeName().parameterizedBy(java.sql.Array::class.asTypeName()),
                    KModifier.PRIVATE
                ).initializer("ArrayList()").build())
            }
        }
    }

    private fun generateInvokeFunction(output : ResultSetOutput) : FunSpec {
        val function = FunSpec.builder("invoke")
        function.addModifiers(KModifier.OPERATOR)
        if(output !is ResultSetOutput.None){
            function.returns(List::class.asTypeName().parameterizedBy(output.extractName()))
        }

        for(param in query.inputs.namedParameters){
            function.addParameter(param.parameterName, param.type.asTypeName())
        }

        function.beginControlFlow("return prepare(${query.inputs.namedParameters.joinToString(", ") { "${it.parameterName} = ${it.parameterName}" }}).use")
        function.addStatement("execute(it)")
        function.addStatement("extract(it)")
        function.endControlFlow()

        return function.build()
    }

    private fun generateExecuteFunction() : FunSpec {
        val function = FunSpec.builder("execute")
        function.addParameter("prepared", PreparedStatement::class)
        function.beginControlFlow("try")
        function.addStatement("prepared.execute()")
        function.nextControlFlow("finally")
        if(hasArrayInputs){
            function.addStatement("__arrays.forEach { it.free() }")
        }
        function.endControlFlow()
        return function.build()
    }

    private fun generatePrepareFunction() : FunSpec {
        val function = FunSpec.builder("prepare")

        function.returns(PreparedStatement::class)

        for(param in query.inputs.namedParameters){
            function.addParameter(param.parameterName, param.type.asTypeName())
        }

        function.addStatement("val connection = connectionProvider.getConnection()")
        function.addStatement("val prepared = connection.prepareStatement(%S)", query.query)
        for((index, param) in query.inputs.orderedPlaceholderList.withIndex()){
            function.addStatement("prepared.setObject(${index+1}, ${param.type.convertToSql(ConvertToSqlContext(param.parameterName))}, ${param.sqlTypeCode})")
        }

        function.addStatement("return prepared")

        return function.build()
    }

    private fun generateExtractFunction(output : ResultSetOutput) : FunSpec {
        val function = FunSpec.builder("extract")
        function.addParameter("prepared", PreparedStatement::class)
        var resultSetIndex = 0
        fun processResultSet(rs : ResultSetOutput){
            when(rs){
                is ResultSetOutput.None -> { }
                is ResultSetOutput.MultiResultSets -> {
                    val startIndex = resultSetIndex
                    val parts = mutableListOf<Int>()
                    rs.parts.withIndex().forEach {(index, it) ->
                        if(index > 0){
                            function.addStatement("prepared.moreResults")
                        }
                        resultSetIndex += 1
                        parts.add(resultSetIndex)
                        processResultSet(it)
                    }
                    function.addStatement("val result${startIndex} = %T(${parts.joinToString(","){ "result$it" }}", rs.wrapper)
                }
                is ResultSetOutput.HasResultSetData -> {
                    function.addStatement("var rs${resultSetIndex} = prepared.resultSet")
                    function.addStatement("val result${resultSetIndex} = mutableListOf<%T>()", output.extractName())

                    function.beginControlFlow("while(rs${resultSetIndex}.next())")
                    for ((columnIndex, column) in rs.resultSet.columns.withIndex()) {
                        function.addStatement(
                            "val out$columnIndex = rs${resultSetIndex}.getObject(\"${column.columnName}\"${if (column.type.isBase) "" else ", ${column.type.sqlRepr}::class.java"}).let { result -> ${
                                column.type.convertFromSql(
                                    ConvertFromSqlContext("result")
                                )
                            } }"
                        )
                    }
                    when (rs) {
                        is ResultSetOutput.DirectType -> {
                            function.addStatement("result${resultSetIndex}.add(out0)")
                        }
                        is ResultSetOutput.Wrapper -> {
                            function.addStatement("result${resultSetIndex}.add(%T(${rs.resultSet.columns.withIndex().joinToString(", ") { (index, it) -> "`${it.columnName}` = out$index" }}))", rs.wrapper)
                        }
                        else -> throw IllegalStateException()
                    }
                    function.endControlFlow()
                }
            }
        }
        processResultSet(output)
        if(output !is ResultSetOutput.None){
            function.returns(List::class.asTypeName().parameterizedBy(output.extractName()))
            function.addStatement("return result0")
        }
        return function.build()
    }

    private val hasArrayInputs = query.inputs.namedParameters.any { it.type.requireArraySupport }
}
