package net.justmachinery.kdbgen.generation.sqlquery
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import net.justmachinery.kdbgen.ConnectionProvider
import net.justmachinery.kdbgen.generation.*
import net.justmachinery.kdbgen.kapt.SqlQuery
import org.postgresql.jdbc.PgConnection
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSetMetaData
import java.util.*
import java.util.regex.Pattern
import javax.annotation.processing.Messager
import javax.lang.model.element.Element
import javax.tools.Diagnostic

internal class SqlQueryWrapperGenerator(
    private val messager : Messager,
    private val settings : Settings
) : AutoCloseable {
    private val connection : Connection
    private val enumTypes : List<EnumType>
    private val renderingContext : RenderingContext
    init {
        DriverManager.registerDriver(org.postgresql.Driver())
        connection = DriverManager.getConnection(
            settings.databaseUrl,
            Properties()
        ) as PgConnection

        enumTypes = generateEnumTypes(connection)
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

    private val generatedPackageName = "net.justmachinery.kdbgen.sql"
    private fun generateCode(){
        val fileBuilder = FileSpec.builder(generatedPackageName, "Queries")
        fileBuilder.addImport("net.justmachinery.kdbgen.utility", "convertFromResultSetObject", "convertToParameterType")

        renderEnumTypes(fileBuilder, enumTypes)

        val globallyOutputtedClasses = mutableSetOf<ClassName>()
        fun generateQueryCode(containerName : String?, query : SqlQueryData) : Pair<TypeSpec?, FunSpec>? {
            val queryHasResult = query.outputs.isNotEmpty()
            val hasDirectResult = query.outputs.size == 1 && query.outputClassName == null
            val shouldGenerateGlobalExplicitlyNamedResultWrapper = query.outputClassName?.packageName == generatedPackageName

            //We always define a result class so that we can get its types for conversion, even if the query only returns
            //one column or returns an explicitly named user data class.
            val resultWrapperClass =
                when {
                    shouldGenerateGlobalExplicitlyNamedResultWrapper -> query.outputClassName!!
                    containerName != null -> ClassName(generatedPackageName, containerName, "${query.name.capitalize()}Result")
                    else -> ClassName(generatedPackageName, "${query.name.capitalize()}Result")
                }
            var localResultClass : TypeSpec? = null

            if(queryHasResult && !globallyOutputtedClasses.contains(resultWrapperClass)) {
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

                if(shouldGenerateGlobalExplicitlyNamedResultWrapper){
                    fileBuilder.addType(resultClassBuilder.build())
                    globallyOutputtedClasses.add(resultWrapperClass)
                } else {
                    localResultClass = resultClassBuilder.build()
                }
            }

            val function = FunSpec.builder(query.name)
            function.receiver(ConnectionProvider::class)

            val namedParameters = query.inputs.groupBy { it.parameterName }

            //Sanity check named parameters
            for(values in namedParameters.values){
                if(!values.all { it == values.first() }) {
                    messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "In query ${query.name}, types of named parameter ${values.first().parameterName} in multiple locations do not match: $values",
                        query.element
                    )
                    return null
                }
            }

            for((name, params) in namedParameters){
                function.addParameter(name, params.first().type)
            }


            function.addStatement("val connection = this.getConnection()")
            function.beginControlFlow("connection.prepareStatement(%S).use", query.query)
            function.addStatement("prepared ->")
            for((index, param) in query.inputs.withIndex()){
                function.addStatement("prepared.setObject(${index+1}, convertToParameterType(${param.parameterName}, \"${param.sqlTypeName}\", connection), ${param.sqlTypeCode})")
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
                    function.addStatement("val out$index = convertFromResultSetObject(rs.getObject(\"${output.columnName}\"), ${resultWrapperClass.simpleName}::`${output.columnName}`.returnType) as %T", output.type)
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

            return Pair(localResultClass, function.build())
        }

        for(query in globalQueries){
            generateQueryCode(null, query)?.let { (resultClass, functionSpec) ->
                resultClass?.let { fileBuilder.addType(it) }
                fileBuilder.addFunction(functionSpec)
            }
        }

        for(container in containerQueries){
            val queryContainerInterface = TypeSpec.interfaceBuilder(container.containerInterfaceName)
            for(query in container.contents){
                generateQueryCode(container.containerInterfaceName, query)?.let { (resultClass, functionSpec) ->
                    resultClass?.let { queryContainerInterface.addType(it) }
                    queryContainerInterface.addFunction(functionSpec)
                }
            }
            fileBuilder.addType(queryContainerInterface.build())
        }

        fileBuilder.build().writeTo(File(settings.outputDirectory))
    }

    override fun close() {
        generateCode()
        connection.close()
    }

    private val globalQueries = mutableListOf<SqlQueryData>()
    private val containerQueries = mutableListOf<QueryContainerContents>()

    fun processGlobalStatement(query : SqlQuery, element : Element){
        getMetadata(query, element)?.let { globalQueries.add(it) }
    }

    fun processQueryContainer(containerName : String, queries : List<Pair<SqlQuery, Element>>){
        containerQueries.add(QueryContainerContents(
            containerName,
            queries.mapNotNull { (annotation, element) -> getMetadata(annotation, element) }
        ))
    }

    private fun getMetadata(query : SqlQuery, element : Element) : SqlQueryData? {
        try {
            val name = query.name
            val statement = query.query
            val outputClassName = query.resultName

            val (replacedQuery, mapping) = replaceNamedParameters(statement)

            val prep = connection.prepareStatement(replacedQuery)
            val metaData: ResultSetMetaData? = prep.metaData
            val parameterMetaData = prep.parameterMetaData!!

            val inputs = (1..parameterMetaData.parameterCount).map {
                var paramName = mapping[it]!!
                val nullable: Boolean
                if (paramName.endsWith("?")) {
                    paramName = paramName.dropLast(1)
                    nullable = true
                } else {
                    nullable = false
                }
                InputParameter(
                    type = getTypeRepr(parameterMetaData.getParameterTypeName(it), nullable),
                    sqlTypeName = parameterMetaData.getParameterTypeName(it),
                    sqlTypeCode = parameterMetaData.getParameterType(it),
                    parameterName = paramName
                )
            }
            val outputs = if (metaData != null) {
                (1..metaData.columnCount).map {
                    OutputColumn(
                        columnName = metaData.getColumnName(it),
                        type = getTypeRepr(
                            metaData.getColumnTypeName(it),
                            metaData.isNullable(it) == ResultSetMetaData.columnNullable
                        )
                    )
                }
            } else {
                listOf()
            }

            val className = when {
                outputClassName.contains('.') -> ClassName.bestGuess(outputClassName)
                outputClassName.isNotBlank() -> ClassName(generatedPackageName, outputClassName)
                else -> null
            }

            val finalQuery = castEnumParameters(statement, inputs)

            return SqlQueryData(name, finalQuery, inputs, outputs, className, element)
        } catch(t : Throwable){
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not process query \"${query.name}\": $t", element)
            return null
        }
    }


    private fun replaceNamedParameters(query: String): Pair<String, Map<Int, String>> {
        val bindings = mutableMapOf<Int, String>()
        val transformed = overNamedParameters(query){parameterName ->
            bindings[bindings.size+1] = parameterName
            "?"
        }
        return Pair(transformed, bindings)
    }
    private fun castEnumParameters(originalQuery: String, typeInfo : List<InputParameter>): String {
        val typeInfoIter = typeInfo.iterator()
        return overNamedParameters(originalQuery) {
            val info = typeInfoIter.next()
            if(renderingContext.postgresTypeToEnum.containsKey(info.sqlTypeName)){
                "CAST(? AS ${info.sqlTypeName})"
            } else {
                "?"
            }
        }
    }
    private fun overNamedParameters(query : String, replacement : (String)->String) : String {
        val matcher = Pattern.compile("(?<!:):(\\w+\\??)").matcher(query)
        val transformed = StringBuffer()
        while (matcher.find()) {
            val parameterName = matcher.group(1)
            matcher.appendReplacement(transformed, replacement(parameterName))
        }
        matcher.appendTail(transformed)
        return transformed.toString()
    }
}

private data class SqlQueryData(
    val name : String,
    val query : String,
    val inputs : List<InputParameter>,
    val outputs : List<OutputColumn>,
    val outputClassName : ClassName?,
    val element : Element
)
private data class InputParameter(val type : TypeName, val sqlTypeName : String, val sqlTypeCode : Int, val parameterName : String)
private data class OutputColumn(val columnName : String, val type : TypeName)

private data class QueryContainerContents(val containerInterfaceName : String, val contents : List<SqlQueryData>)