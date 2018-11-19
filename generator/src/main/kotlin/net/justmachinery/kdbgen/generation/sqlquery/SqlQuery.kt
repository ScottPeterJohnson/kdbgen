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
import java.sql.ParameterMetaData
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

    private fun generateCode(){
        val packageName = "$kdbGen.sql"
        val builder = FileSpec.builder(packageName, "Queries")

        for(query in queries){
            val resultClass : ClassName?
            if(query.outputs.isNotEmpty()) {
                resultClass = ClassName(packageName, "${query.name.capitalize()}Result")
                run {
                    val resultClassBuilder = TypeSpec.classBuilder(resultClass)
                    resultClassBuilder.addModifiers(KModifier.DATA)
                    val primaryConstructor = FunSpec.constructorBuilder()
                    for(column in query.outputs){
                        val repr = getTypeRepr(column.typeName, column.nullable)
                        primaryConstructor.addParameter(column.columnName, repr)
                        resultClassBuilder.addProperty(PropertySpec.builder(column.columnName, repr).initializer(column.columnName).build())
                    }
                    resultClassBuilder.primaryConstructor(primaryConstructor.build())
                    builder.addType(resultClassBuilder.build())
                }
            } else {
                resultClass = null
            }


            run {
                val function = FunSpec.builder(query.name)
                function.receiver(ConnectionProvider::class)
                if(resultClass != null){
                    function.returns(List::class.asClassName().parameterizedBy(resultClass))
                }

                val namedParameters = query.inputs.groupBy { it.parameterName }

                //Sanity check named parameters
                for(values in namedParameters.values){
                    if(!values.all { it == values.first() }) {
                        throw RuntimeException("Types of named parameter ${values.first().parameterName} in multiple locations do not match: $values")
                    }
                }

                for((name, params) in namedParameters){
                    function.addParameter(name, getTypeRepr(params.first().typeName, params.first().nullable))
                }

                function.beginControlFlow("this.getConnection().use")
                    function.addStatement("connection ->")
                    function.beginControlFlow("connection.prepareStatement(%S).use", query.query)
                        function.addStatement("prepared ->")
                        for((index, param) in query.inputs.withIndex()){
                            function.addStatement("prepared.setObject(${index+1}, ${param.parameterName}, ${param.sqlTypeCode})")
                        }
                        if(resultClass != null){
                            function.addStatement("val rs = prepared.executeQuery()")
                            function.addStatement("val results = mutableListOf<$resultClass>()")
                            function.beginControlFlow("while(rs.next())")
                            for((index, output) in query.outputs.withIndex()){
                                OutputColumn::columnName.returnType
                                val repr = getTypeRepr(output.typeName, output.nullable)
                                function.addStatement("val out$index = net.justmachinery.kdbgen.utility.convertFromResultSetObject(rs.getObject(${index+1}), $resultClass::`${output.columnName}`.returnType) as %T", repr)
                            }
                            function.addStatement("results.add($resultClass(${query.outputs.indices.joinToString(", ") { "out$it" }}))")
                            function.endControlFlow()
                            function.addStatement("return results")
                        } else {
                            function.addStatement("prepared.execute()")
                        }

                    function.endControlFlow()
                function.endControlFlow()

                builder.addFunction(function.build())
            }
        }

        builder.build().writeTo(File(settings.outputDirectory))
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

    fun processStatement(name : String, statement : String){
        val (replaced, mapping) = replaceNamedParameters(statement)

        val prep = connection.prepareStatement(replaced)
        val metaData : ResultSetMetaData? = prep.metaData
        val parameterMetaData = prep.parameterMetaData!!

        val inputs = (1..parameterMetaData.parameterCount).map {
            InputParameter(
                typeName = parameterMetaData.getParameterTypeName(it),
                nullable = parameterMetaData.isNullable(it) == ParameterMetaData.parameterNullable,
                sqlTypeCode = parameterMetaData.getParameterType(it),
                parameterName = mapping[it]!!
            )
        }
        val outputs = if (metaData != null) {
            (1..metaData.columnCount).map {
                OutputColumn(
                    columnName = metaData.getColumnName(it),
                    typeName = metaData.getColumnTypeName(it),
                    nullable = metaData.isNullable(it) == ResultSetMetaData.columnNullable
                )
            }
        } else {
            listOf()
        }
        queries.add(
            SqlQueryData(name, replaced, inputs, outputs)
        )
    }


    fun replaceNamedParameters(query: String): Pair<String, Map<Int, String>> {
        val bindings = mutableMapOf<Int, String>()
        val pattern = Pattern.compile(":(\\w+)")
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

private data class SqlQueryData(val name : String, val query : String, val inputs : List<InputParameter>, val outputs : List<OutputColumn>)
private data class InputParameter(val typeName : String, val nullable : Boolean, val sqlTypeCode : Int, val parameterName : String)
private data class OutputColumn(val columnName : String, val typeName : String, val nullable : Boolean)