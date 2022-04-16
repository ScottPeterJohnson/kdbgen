package net.justmachinery.kdbgen.generation.sqlquery

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import net.justmachinery.kdbgen.BatchPreparation
import net.justmachinery.kdbgen.ConnectionProvider
import net.justmachinery.kdbgen.KdbGenQuery
import net.justmachinery.kdbgen.generation.ConvertFromSqlContext
import net.justmachinery.kdbgen.generation.ConvertToSqlContext
import java.lang.IllegalStateException
import java.sql.Connection
import java.sql.PreparedStatement
import javax.tools.Diagnostic

internal class GenerateQuery(
    val generateCode: GenerateCode,
    val fileBuilder : FileSpec.Builder,
    val container : TypeSpec.Builder?,
    val containerName : String?,
    val query : SqlQueryData
) {
    fun generateClass(autogenName : String, cb: (TypeSpec.Builder, ClassName)->Unit) : ClassName {
        val finalName = query.name.capitalize() + autogenName
        val className = when {
            container != null -> ClassName(generatedPackageName, containerName!!, finalName)
            else -> ClassName(generatedPackageName, finalName)
        }
        val builder = TypeSpec.classBuilder(className)
        cb(builder, className)
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
        return generateClass("Query"){ it, name ->
            val batchClass = name.nestedClass("Batch")
            val paramHolder = name.nestedClass("Params")
            it.superclass(KdbGenQuery::class.asTypeName().parameterizedBy(paramHolder, output.extractName(), batchClass))
            val primaryConstructor = FunSpec.constructorBuilder()
                .addPropertyParameter(it, "connectionProvider", ConnectionProvider::class.asTypeName(), modifier = KModifier.OVERRIDE)
            it.primaryConstructor(primaryConstructor.build())
            it.addProperty(
                PropertySpec.builder("sql", String::class.asTypeName(), KModifier.OVERRIDE)
                    .initializer("%S", query.query)
                    .build())
            generateQueryParamHolder(it, paramHolder)

            it.addFunction(generateInvokeFunction(output, paramHolder))
            it.addFunction(generateSetParameters(paramHolder))
            it.addFunction(generateExtractFunction(output))

            generateBatchPreparation(
                parent = it,
                parentName = name,
                batchPrepName = batchClass,
                resultName = output.extractName(),
                paramsName = paramHolder
            )

            if(hasArrayInputs){
                it.addProperty(PropertySpec.builder(
                    name = "__arrays",
                    type = ArrayList::class.asTypeName().parameterizedBy(java.sql.Array::class.asTypeName()),
                    KModifier.OVERRIDE, KModifier.PROTECTED
                ).initializer("ArrayList()").build())
            }
        }
    }

    private fun generateBatchPreparation(
        parent : TypeSpec.Builder,
        parentName : ClassName,
        batchPrepName : ClassName,
        resultName: TypeName,
        paramsName: ClassName,
    ){
        parent.addType(TypeSpec.classBuilder(batchPrepName)
            .apply {
                val primaryConstructor = FunSpec.constructorBuilder()
                primaryConstructor.addPropertyParameter(
                    clazz = this,
                    name = "query",
                    type = parentName,
                    modifier = KModifier.OVERRIDE
                )
                primaryConstructor.addPropertyParameter(
                    clazz = this,
                    name = "connection",
                    type = Connection::class.asTypeName(),
                    modifier = KModifier.OVERRIDE
                )
                primaryConstructor.addPropertyParameter(
                    clazz = this,
                    name = "prepared",
                    type = PreparedStatement::class.asTypeName(),
                    modifier = KModifier.OVERRIDE
                )
                this.primaryConstructor(primaryConstructor.build())
                    .addSuperinterface(BatchPreparation::class.asTypeName().parameterizedBy(paramsName, resultName))
                this.addProperty(PropertySpec.builder("count", Int::class, KModifier.OVERRIDE).mutable(true).initializer("0").build())
                this.addFunction(generateBatchInvokeFunction(paramsName))
            }
            .build())
        parent.addFunction(FunSpec.builder("instantiateBatch")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("connection", Connection::class)
            .addParameter("prepared", PreparedStatement::class)
            .returns(TypeVariableName(name = "Batch"))
            .addStatement("return %T(this, connection, prepared)", batchPrepName)
            .build()
        )
    }

    private fun generateQueryParamHolder(parent : TypeSpec.Builder, resultName : ClassName){
        parent.addType(TypeSpec.classBuilder(resultName)
            .apply {
                if(query.inputs.namedParameters.isNotEmpty()){
                    addModifiers(KModifier.DATA)
                }
                val primaryConstructor = FunSpec.constructorBuilder()
                for(param in query.inputs.namedParameters){
                    primaryConstructor.addPropertyParameter(
                        clazz = this,
                        name = param.parameterName,
                        type = param.type.asTypeName(),
                        modifier = KModifier.PUBLIC
                    )
                }
                this.primaryConstructor(primaryConstructor.build())
            }
            .build())
    }

    private fun generateBatchInvokeFunction(paramHolder: ClassName) : FunSpec {
        val function = FunSpec.builder("add")

        for(param in query.inputs.namedParameters){
            function.addParameter(param.parameterName, param.type.asTypeName())
        }

        function.addStatement("add(${paramHolder}(${query.inputs.namedParameters.joinToString(", "){ "${it.parameterName} = ${it.parameterName}"}}))")

        return function.build()
    }

    private fun generateInvokeFunction(output : ResultSetOutput, paramHolder: ClassName) : FunSpec {
        val function = FunSpec.builder("invoke")
        function.addModifiers(KModifier.OPERATOR)

        for(param in query.inputs.namedParameters){
            function.addParameter(param.parameterName, param.type.asTypeName())
        }

        if(output !is ResultSetOutput.None){
            function.returns(List::class.asTypeName().parameterizedBy(output.extractName()))
            function.addStatement("return invoke(${paramHolder}(${query.inputs.namedParameters.joinToString(", "){ "${it.parameterName} = ${it.parameterName}"}}))")
        } else {
            function.addStatement("execute(${paramHolder}(${query.inputs.namedParameters.joinToString(", "){ "${it.parameterName} = ${it.parameterName}"}}))")
        }


        return function.build()
    }

    private fun generateSetParameters(paramHolder : ClassName) : FunSpec {
        val function = FunSpec.builder("setParameters")
        function.addModifiers(KModifier.OVERRIDE)
        function.addParameter("connection", Connection::class)
        function.addParameter("prepared", PreparedStatement::class)
        function.addParameter("params", paramHolder)

        for((index, param) in query.inputs.orderedPlaceholderList.withIndex()){
            function.addStatement("prepared.setObject(${index+1}, ${param.type.convertToSql(ConvertToSqlContext("params." + param.parameterName))}, ${param.sqlTypeCode})")
        }

        return function.build()
    }

    private fun generateExtractFunction(output : ResultSetOutput) : FunSpec {
        val function = FunSpec.builder("extract")
        function.addModifiers(KModifier.OVERRIDE)
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
        function.returns(List::class.asTypeName().parameterizedBy(output.extractName()))
        if(output !is ResultSetOutput.None){
            function.addStatement("return result0")
        } else {
            function.addStatement("return emptyList()")
        }
        return function.build()
    }

    private val hasArrayInputs = query.inputs.namedParameters.any { it.type.requireArraySupport }
}
