package net.justmachinery.kdbgen.generation.sqlquery
import com.impossibl.postgres.api.jdbc.PGConnection
import com.impossibl.postgres.jdbc.PGDriver
import com.impossibl.postgres.jdbc.PGParameterMetaData
import com.impossibl.postgres.protocol.ResultField
import com.impossibl.postgres.types.Type
import com.squareup.kotlinpoet.ClassName
import net.justmachinery.kdbgen.generation.AnnotationContext
import net.justmachinery.kdbgen.generation.GenerateElement
import net.justmachinery.kdbgen.generation.TypeContext
import net.justmachinery.kdbgen.generation.TypeRepr
import net.justmachinery.kdbgen.kapt.SqlQuery
import org.apache.commons.lang3.reflect.FieldUtils
import java.sql.DriverManager
import java.sql.ResultSetMetaData
import java.sql.Types
import java.util.*
import java.util.regex.Pattern


internal const val generatedPackageName = "net.justmachinery.kdbgen.sql"

internal class KdbGenerator(
    val context : AnnotationContext,
    val prelude : String
) : AutoCloseable {
    private val connection : PGConnection
    val typeContext : TypeContext
    init {
        DriverManager.registerDriver(PGDriver())
        connection = DriverManager.getConnection(
            context.settings.databaseUrl,
            Properties()
        ) as PGConnection

        initializePrelude()

        typeContext = TypeContext(context.settings)
    }

    private fun initializePrelude(){
        if(prelude.isNotBlank()){
            connection.createStatement().use {
                it.execute(prelude)
            }
        }
    }




    context(QueryElement)
    private fun getTypeRepr(oid : Int, nullable : Boolean) : TypeRepr {
        return typeContext.mapPostgresType(connection, oid, nullable)
    }



    override fun close() {
        val generator = GenerateCode(this)
        generator.generateCode()
        connection.close()
    }

    val globalQueries = mutableListOf<SqlQueryData>()
    val containerQueries = mutableListOf<QueryContainerContents>()

    fun processGlobalStatement(qe: QueryElement){
        qe.run {
            getMetadata()?.let { globalQueries.add(it) }
        }
    }

    fun processQueryContainer(parent: GenerateElement, containerName : String, queries : List<QueryElement>){
        containerQueries.add(QueryContainerContents(
            parent = parent,
            containerInterfaceName = containerName,
            contents = queries.mapNotNull { qe ->
                qe.run { getMetadata() }
            }
        ))
    }

    class QueryElement(
        val query : SqlQuery,
        val element: GenerateElement
    )
    context(QueryElement)
    private fun getMetadata() : SqlQueryData? {
        try {
            val name = query.name
            val statement = query.query
            val outputClassName = query.resultName

            val (replacedQuery, mapping) = replaceNamedParameters(statement)

            val prep = connection.prepareStatement(replacedQuery)
            prep.use {
                val parameterMetaData = prep.parameterMetaData!!.unwrap(PGParameterMetaData::class.java)

                @Suppress("UNCHECKED_CAST")
                val paramOids = FieldUtils.readField(parameterMetaData, "parameterTypes", true) as Array<Type>
                val rawInputs = (1..parameterMetaData.parameterCount).map {
                    var paramName = mapping.getValue(it)
                    val nullable: Boolean
                    if (paramName.endsWith("?")) {
                        paramName = paramName.dropLast(1)
                        nullable = true
                    } else {
                        nullable = false
                    }
                    val typeRepr = getTypeRepr(paramOids[it-1].oid, nullable)
                    InputParameter(
                        type = typeRepr,
                        sqlTypeName = parameterMetaData.getParameterTypeName(it),
                        //It's unclear why for enums the parameter metadata returns the VARCHAR type.
                        sqlTypeCode = if(typeRepr.isEnum) Types.OTHER else parameterMetaData.getParameterType(it),
                        parameterName = paramName
                    )
                }

                val rawInputsByName = rawInputs.groupBy { it.parameterName }

                for(values in rawInputsByName.values){
                    if(!values.all { it == values.first() }) {
                        throw GeneratingException(
                            "In query ${query.name}, types of named parameter ${values.first().parameterName} in multiple locations do not match: $values",
                            element
                        )
                    }
                }

                val namedParameters = rawInputsByName.values.map { it.first() }

                /*prep.unwrap(PGPreparedStatement::class.java).executeWithFlags(QueryExecutor.QUERY_ONESHOT or QueryExecutor.QUERY_DESCRIBE_ONLY or QueryExecutor.QUERY_SUPPRESS_BEGIN)*/




                val resultSets = mutableListOf<ResultSetData>()
                val metaData: ResultSetMetaData = prep.metaData
                @Suppress("UNCHECKED_CAST") val resultOids = (FieldUtils.readField(metaData, "resultFields", true) as Array<ResultField>).map { it.typeRef.oid }

                val outputs = ((1..metaData.columnCount).map {
                    OutputColumn(
                        columnName = metaData.getColumnLabel(it),
                        type = getTypeRepr(
                            oid = resultOids[it-1],
                            nullable = if(query.columnCanBeNull.isNotEmpty() && query.columnCanBeNull.size >= it){
                                query.columnCanBeNull[it-1]
                            } else {
                                metaData.isNullable(it) != ResultSetMetaData.columnNoNulls
                            }
                        )
                    )
                })
                resultSets.add(ResultSetData(
                    columns = outputs,
                    innerResultName = query.subResultNames.getOrNull(resultSets.size)?.let { if(it.isBlank()) null else it }
                ))

                return SqlQueryData(
                    name = name,
                    query = replacedQuery,
                    inputs = InputInfo(
                        namedParameters = namedParameters,
                        orderedPlaceholderList = rawInputs
                    ),
                    resultSets = resultSets,
                    outerResultName = outputClassName,
                    element = element
                )
            }
        } catch(t : Throwable){
            context.gen.logError("Could not process query \"${query.name}\": $t\n${t.stackTrace.joinToString("\n")}", element)
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

internal data class SqlQueryData(
    val name : String,
    val query : String,
    val inputs : InputInfo,
    val resultSets : List<ResultSetData>,
    val outerResultName : String?,
    val element : GenerateElement
)
internal data class InputInfo(
    val namedParameters : List<InputParameter>,
    val orderedPlaceholderList : List<InputParameter>
)
internal class ResultSetData(
    val columns : List<OutputColumn>,
    val innerResultName : String?
)
internal data class InputParameter(val type : TypeRepr, val sqlTypeName : String, val sqlTypeCode : Int, val parameterName : String)
internal data class OutputColumn(val columnName : String, val type : TypeRepr)

internal data class QueryContainerContents(val parent : GenerateElement, val containerInterfaceName : String, val contents : List<SqlQueryData>)