package net.justmachinery.kdbgen.generation.sqlquery
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import net.justmachinery.kdbgen.generation.EnumType
import net.justmachinery.kdbgen.generation.RenderingContext
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.generation.generateEnumTypes
import net.justmachinery.kdbgen.kapt.SqlQuery
import org.postgresql.core.QueryExecutor
import org.postgresql.jdbc.PgConnection
import org.postgresql.jdbc.PgStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSetMetaData
import java.util.*
import java.util.regex.Pattern
import javax.annotation.processing.Messager
import javax.lang.model.element.Element
import javax.tools.Diagnostic


internal const val generatedPackageName = "net.justmachinery.kdbgen.sql"

internal class SqlQueryWrapperGenerator(
    val messager : Messager,
    val settings : Settings
) : AutoCloseable {
    private val connection : Connection
    val enumTypes : List<EnumType>
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



    override fun close() {
        val generator = GenerateCode(this)
        generator.generateCode()
        connection.close()
    }

    val globalQueries = mutableListOf<SqlQueryData>()
    val containerQueries = mutableListOf<QueryContainerContents>()

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
            prep.use {
                val parameterMetaData = prep.parameterMetaData!!


                val inputs = (1..parameterMetaData.parameterCount).map {
                    var paramName = mapping.getValue(it)
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

                prep.unwrap(PgStatement::class.java).executeWithFlags(QueryExecutor.QUERY_ONESHOT or QueryExecutor.QUERY_DESCRIBE_ONLY or QueryExecutor.QUERY_SUPPRESS_BEGIN)



                fun String.toClassName() : ClassName? {
                    return when {
                        this.contains('.') -> ClassName.bestGuess(this)
                        this.isNotBlank() -> ClassName(generatedPackageName, this)
                        else -> null
                    }
                }
                val className = outputClassName.toClassName()

                val resultSets = mutableListOf<ResultSetData>()
                //Note that for weird Postgres reasons, we cannot get updateCounts with a
                //describe-only query. These will have to be skipped at query execution time.
                while(true){
                    if (prep.updateCount != -1 || prep.resultSet == null) {
                        resultSets.add(ResultSetData(
                            columns = emptyList(),
                            innerResultName = null
                        ))
                    } else {
                        val metaData: ResultSetMetaData = prep.metaData
                        val outputs = ((1..metaData.columnCount).map {
                            OutputColumn(
                                columnName = metaData.getColumnName(it),
                                type = getTypeRepr(
                                    metaData.getColumnTypeName(it),
                                    metaData.isNullable(it) == ResultSetMetaData.columnNullable
                                )
                            )
                        })
                        resultSets.add(ResultSetData(
                            columns = outputs,
                            innerResultName = query.subResultNames.getOrNull(resultSets.size)?.let { if(it.isBlank()) null else it }?.let { it.toClassName() }
                        ))
                    }

                    if(!prep.moreResults){
                       if(prep.updateCount == -1){
                           break
                       }
                    }
                }



                val finalQuery = castEnumParameters(statement, inputs)

                return SqlQueryData(
                    name = name,
                    query = finalQuery,
                    inputs = inputs,
                    resultSets = resultSets,
                    outerResultName = className,
                    element = element
                )
            }
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

internal data class SqlQueryData(
    val name : String,
    val query : String,
    val inputs : List<InputParameter>,
    val resultSets : List<ResultSetData>,
    val outerResultName : ClassName?,
    val element : Element
)
internal class ResultSetData(
    val columns : List<OutputColumn>,
    val innerResultName : ClassName?
)
internal data class InputParameter(val type : TypeName, val sqlTypeName : String, val sqlTypeCode : Int, val parameterName : String)
internal data class OutputColumn(val columnName : String, val type : TypeName)

internal data class QueryContainerContents(val containerInterfaceName : String, val contents : List<SqlQueryData>)