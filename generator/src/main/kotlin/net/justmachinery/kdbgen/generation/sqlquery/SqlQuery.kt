package net.justmachinery.kdbgen.generation.sqlquery
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import net.justmachinery.kdbgen.dsl.ConnectionProvider
import net.justmachinery.kdbgen.generation.RenderingContext
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.generateEnumTypes
import net.justmachinery.kdbgen.kdbGen
import org.postgresql.jdbc.PgConnection
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSetMetaData
import java.util.*
import java.util.regex.Pattern

internal class SqlQueryWrapperGenerator(
    private val settings : Settings
) : AutoCloseable {
    private val connection : Connection
    private val renderingContext : RenderingContext
    init {
        DriverManager.registerDriver(org.postgresql.Driver())
        connection = DriverManager.getConnection(
            settings.databaseUrl,
            Properties()
        ) as PgConnection
        connection.autoCommit = false

        val enumTypes = generateEnumTypes(connection)
        renderingContext = RenderingContext(settings, enumTypes)
    }

    private fun convertTypeRepr(repr : RenderingContext.TypeRepr) : TypeName {
        return ClassName.bestGuess(repr.base)
            .let {
                if(repr.params.isNotEmpty()) it.parameterizedBy(*repr.params.map { param -> convertTypeRepr(param) }.toTypedArray()) else it
            }
            .let {
                if(repr.nullable){ it.asNullable() } else { it.asNonNull() }
            }
    }

    private fun getTypeRepr(name : String, nullable : Boolean) : TypeName {
        return convertTypeRepr(renderingContext.mapPostgresType(name, nullable))
    }

    private val generatedPackageName = "$kdbGen.sql"
    private fun generateCode(){
        val fileBuilder = FileSpec.builder(generatedPackageName, "Queries")
        fileBuilder.addImport("net.justmachinery.kdbgen.utility", "convertFromResultSetObject")

        val createdOutputClasses = mutableSetOf<ClassName>()
        for(query in queries){
            val queryHasResult = query.outputs.isNotEmpty()
            val hasDirectResult = query.outputs.size == 1 && query.outputClassName == null

            val resultWrapperClass =
                if(query.outputClassName?.packageName == generatedPackageName) query.outputClassName
                else ClassName(generatedPackageName, "${query.name.capitalize()}Result")
            if(queryHasResult && !createdOutputClasses.contains(resultWrapperClass)) {
                //We always define a result class so we can get its types for conversion. It may be private though.
                createdOutputClasses.add(resultWrapperClass)

                val resultClassBuilder = TypeSpec.classBuilder(resultWrapperClass)
                resultClassBuilder.addModifiers(KModifier.DATA)
                if(query.outputClassName != null && resultWrapperClass != query.outputClassName){
                    resultClassBuilder.addModifiers(KModifier.PRIVATE)
                }

                val primaryConstructor = FunSpec.constructorBuilder()
                for(column in query.outputs){
                    primaryConstructor.addParameter(column.columnName, column.type)
                    resultClassBuilder.addProperty(PropertySpec.builder(column.columnName, column.type).initializer(column.columnName).build())
                }
                resultClassBuilder.primaryConstructor(primaryConstructor.build())
                fileBuilder.addType(resultClassBuilder.build())
            }

            val function = FunSpec.builder(query.name)
            function.receiver(ConnectionProvider::class)

            val namedParameters = query.inputs.groupBy { it.parameterName }

            //Sanity check named parameters
            for(values in namedParameters.values){
                if(!values.all { it == values.first() }) {
                    throw RuntimeException("Types of named parameter ${values.first().parameterName} in multiple locations do not match: $values")
                }
            }

            for((name, params) in namedParameters){
                function.addParameter(name, params.first().type)
            }

            function.beginControlFlow("this.getConnection().use")
            function.addStatement("connection ->")
            function.beginControlFlow("connection.prepareStatement(%S).use", query.query)
            function.addStatement("prepared ->")
            for((index, param) in query.inputs.withIndex()){
                function.addStatement("prepared.setObject(${index+1}, ${param.parameterName}, ${param.sqlTypeCode})")
            }

            if(queryHasResult){
                val result = when {
                    hasDirectResult -> query.outputs.first().type
                    query.outputClassName != null -> query.outputClassName
                    else -> resultWrapperClass
                }
                function.returns(List::class.asClassName().parameterizedBy(result))
                function.addStatement("val rs = prepared.executeQuery()")
                function.addStatement("val results = mutableListOf<$result>()")
                function.beginControlFlow("while(rs.next())")
                for((index, output) in query.outputs.withIndex()){
                    function.addStatement("val out$index = convertFromResultSetObject(rs.getObject(${index+1}), $resultWrapperClass::`${output.columnName}`.returnType) as %T", output.type)
                }
                if(hasDirectResult) {
                    function.addStatement("results.add(out0)")
                } else {
                    function.addStatement("results.add($result(${query.outputs.withIndex().joinToString(", ") { (index, it) -> "`${it.columnName}` = out$index" }}))")
                }
                function.endControlFlow()
                function.addStatement("return results")
            } else {
                function.addStatement("prepared.execute()")
            }

            function.endControlFlow()
            function.endControlFlow()

            fileBuilder.addFunction(function.build())
        }

        fileBuilder.build().writeTo(File(settings.outputDirectory))
    }

/*    private fun generateConvertParameter(outputIndex : Int, output : OutputColumn, funSpec: FunSpec.Builder){
        val repr = getTypeRepr(output.typeName, output.nullable)
        funSpec.beginControlFlow("val ${output.columnName} : %T = when(val obj = resultSet.getObject($outputIndex))")
        if(repr.nullable){
            funSpec.addStatement("null -> null")
        }
        if(output.typeName.startsWith("_") *//* Array *//*){

        }
        funSpec.addStatement("else -> obj")
        funSpec.endControlFlow()
    }*/

    override fun close() {
        generateCode()
        connection.rollback()
        connection.close()
    }

    private val queries = mutableListOf<SqlQueryData>()

    fun processStatement(name : String, statement : String, outputClassName : String){
        val (replaced, mapping) = replaceNamedParameters(statement)

        val prep = connection.prepareStatement(replaced)
        val metaData : ResultSetMetaData? = prep.metaData
        val parameterMetaData = prep.parameterMetaData!!

        val inputs = (1..parameterMetaData.parameterCount).map {
            var paramName = mapping[it]!!
            val nullable : Boolean
            if(paramName.endsWith("?")){
                paramName = paramName.dropLast(1)
                nullable = true
            } else {
                nullable = false
            }
            InputParameter(
                type = getTypeRepr(parameterMetaData.getParameterTypeName(it), nullable),
                sqlTypeCode = parameterMetaData.getParameterType(it),
                parameterName = paramName
            )
        }
        val outputs = if (metaData != null) {
            (1..metaData.columnCount).map {
                OutputColumn(
                    columnName = metaData.getColumnName(it),
                    type = getTypeRepr(metaData.getColumnTypeName(it), metaData.isNullable(it) == ResultSetMetaData.columnNullable)
                )
            }
        } else {
            listOf()
        }

        val className  = when {
            outputClassName.contains('.') -> ClassName.bestGuess(outputClassName)
            outputClassName.isNotBlank() -> ClassName(generatedPackageName, outputClassName)
            else -> null
        }

        queries.add(
            SqlQueryData(name, replaced, inputs, outputs, className)
        )
    }


    private fun replaceNamedParameters(query: String): Pair<String, Map<Int, String>> {
        val bindings = mutableMapOf<Int, String>()
        val pattern = Pattern.compile(":(\\w+\\??)")
        val matcher = pattern.matcher(query)

        val transformed = StringBuilder()
        while (matcher.find()) {
            val parameterName = matcher.group(1)
            bindings[bindings.size+1] = parameterName
            matcher.appendReplacement(transformed, "?")
        }
        matcher.appendTail(transformed)
        return Pair(transformed.toString(), bindings)
    }
}

private data class SqlQueryData(val name : String, val query : String, val inputs : List<InputParameter>, val outputs : List<OutputColumn>, val outputClassName : ClassName?)
private data class InputParameter(val type : TypeName, val sqlTypeCode : Int, val parameterName : String)
private data class OutputColumn(val columnName : String, val type : TypeName)