package net.justmachinery.kdbgen.generation

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import net.justmachinery.kdbgen.utility.Json
import net.justmachinery.kdbgen.utility.onlyWhen
import org.postgresql.jdbc.PgConnection
import java.io.File
import java.nio.file.Paths
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.*

const val kdbGen = "net.justmachinery.kdbgen"
const val commonTypesPackage = "$kdbGen.common"
const val commonTimestamp = "CommonTimestamp"
const val commonTimestampFull = "$commonTypesPackage.$commonTimestamp"
const val commonUuid = "CommonUUID"
const val commonUuidFull = "$commonTypesPackage.$commonUuid"
const val defaultOutputDirectory = "build/generated-sources/kotlin"
const val defaultEnumPackage = "net.justmachinery.kdbgen.enums"
const val defaultDataPackage = "net.justmachinery.kdbgen.tables"

class Settings(parser: ArgParser) {
	val databaseUrl by parser.storing("URL of database to connect to (including user/pass)")
	private val outputDirectory by parser.storing("Directory to output generated source files to").default(
			defaultOutputDirectory)
	private val dslOutputDirectory by parser.storing("Directory to output DSL helpers to, if different than output directory").default(
			null)
	val useCommonTypes by parser.flagging("Outputs common JS/JVM types instead of UUID/Timestamp.")
	val enumPackage by parser.storing("Package to output enum classes to").default(defaultEnumPackage)
	val dataPackage by parser.storing("Package to output beans and DSL to").default(defaultDataPackage)
	val dataAnnotation by parser.adding("Fully qualified annotations to add to emitted data classes, for e.g. serialization")
	val mutableData by parser.flagging("Whether to generate properties on data classes as var instead of val")

	private fun directory(directory: String, `package`: String) = Paths.get(directory,
			`package`.replace(".", "/")).toString()

	fun enumDirectory(): String = directory(outputDirectory, enumPackage)
	fun dataDirectory(): String = directory(outputDirectory, dataPackage)
	fun commonTypesDirectory(): String = directory(outputDirectory, commonTypesPackage)
	fun dslDirectory(): String = directory(dslOutputDirectory ?: outputDirectory, dataPackage)
}

fun main(args: Array<String>) {
	val settings = Settings(ArgParser(args))
	run(settings)
}

fun run(settings: Settings) {
	File(settings.enumDirectory()).deleteRecursively()
	File(settings.dataDirectory()).deleteRecursively()
	File(settings.dslDirectory()).deleteRecursively()
	File(settings.commonTypesDirectory()).deleteRecursively()

	val context = constructContext(settings)
	for(enum in context.postgresTypeToEnum.values){
		renderEnumType(context.settings, enum)
	}

	for (type in context.tables) {
		val renderer = DslRenderer(type, context)
		renderer.render()
	}

	if(settings.useCommonTypes){
		renderCommonTypes(settings)
	}
}


private fun constructContext(settings : Settings) : RenderingContext {
	val connection = DriverManager.getConnection(settings.databaseUrl, Properties()) as PgConnection

	//Generate enum types from Postgres enums
	val userEnumTypes = generateEnumTypes(connection)

	//Fetch table data
	val tablesResultSet = connection.metaData.getTables(null, null, "", arrayOf("TABLE"))
	val types = mutableListOf<PostgresTable>()
	while (tablesResultSet.next()) {
		val rawTableName = tablesResultSet.getString("table_name")
		val properties = mutableListOf<PostgresTableColumn>()

		val columnsResultSet = connection.metaData.getColumns(null, null, rawTableName, null)
		while (columnsResultSet.next()) {
			val rawName = columnsResultSet.getString("column_name")
			val postgresTypeName = columnsResultSet.getString("type_name")
			val nullable = columnsResultSet.getString("is_nullable") != "NO"
			properties.add(PostgresTableColumn(
					rawName = rawName,
					postgresType = postgresTypeName,
					nullable = nullable,
					defaultable = nullable || columnsResultSet.getString("column_def") != null
			))
		}
		types.add(PostgresTable(rawName = rawTableName,
				postgresTableColumns = properties))
	}

	return RenderingContext(types, settings, userEnumTypes)
}


internal class RenderingContext(
		val tables : List<PostgresTable>,
		val settings: Settings,
		enums: List<EnumType>) {
	internal val postgresTypeToEnum = enums.associateBy { it.postgresName }

	internal val PostgresTableColumn.kotlinType
		get() = renderRaw(mapPostgresType(this.postgresType, this.nullable))
	internal val PostgresTableColumn.kotlinKType
		get() = renderType(mapPostgresType(this.postgresType, this.nullable))

	private fun renderRaw(repr : TypeRepr) : String {
		return repr.base.plus("<${repr.params.map(::renderRaw).joinToString(", ")}>".onlyWhen(repr.params.isNotEmpty())).plus("?".onlyWhen(repr.nullable))
	}
	private fun renderType(repr : TypeRepr) : String {
		var result = if(repr.params.isEmpty()){
			"${repr.base}::class.starProjectedType"
		} else {
			val typeArgs = repr.params.map { "KTypeProjection(KVariance.INVARIANT, ${renderType(it)})"}
			"${repr.base}::class.createType(listOf(${typeArgs.joinToString(", ")}))"
		}
		if(repr.nullable){
			result += ".withNullability(true)"
		}
		return result
	}

	private data class TypeRepr(val base : String, val nullable : Boolean, val params : List<TypeRepr>)
	private fun mapPostgresType(postgresType: String, nullable: Boolean): TypeRepr {
		val defaultType = when (postgresType) {
			"bigint" -> Long::class
			"int8" -> Long::class
			"bigserial" -> Long::class
			"boolean" -> Boolean::class
			"bool" -> Boolean::class
			"inet" -> String::class
			"integer" -> Int::class
			"int" -> Int::class
			"int4" -> Int::class
			"json" -> Json::class
			"jsonb" -> Json::class
			"text" -> String::class
			"timestamp" -> if (!settings.useCommonTypes) Timestamp::class else null
			"uuid" -> if (!settings.useCommonTypes) UUID::class else null
			else -> null
		}
		if (defaultType != null) {
			return TypeRepr(defaultType.qualifiedName!!, nullable, emptyList())
		}
		if(postgresType == "timestamp"){
			return TypeRepr(commonTimestampFull, nullable, emptyList())
		}
		if(postgresType == "uuid"){
			return TypeRepr(commonUuidFull, nullable, emptyList())
		}

		if (postgresTypeToEnum.containsKey(postgresType)) {
			return TypeRepr(
					"${settings.enumPackage}.${postgresTypeToEnum[postgresType]!!.className}",
					nullable,
					emptyList()
			)
		}
		if (postgresType.startsWith("_")) {
			val arrayType = mapPostgresType(postgresType.substring(startIndex = 1), true)
			return TypeRepr("kotlin.collections.List", nullable, listOf(arrayType))
		}
		throw IllegalStateException("Unknown postgres type $postgresType")
	}
}

internal data class PostgresTable(val rawName: String, val postgresTableColumns: List<PostgresTableColumn>) {
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}

internal data class PostgresTableColumn(val rawName: String,
                                        val postgresType: String,
                                        val nullable: Boolean,
                                        val defaultable: Boolean) {
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}


//Format is TypeName
internal fun underscoreToCamelCaseTypeName(name: String): String {
	return name.split("_").map(String::capitalize).joinToString("")
}

//Format is memberName
internal fun underscoreToCamelCaseMemberName(name: String): String {
	return name.split("_").mapIndexed { index, it -> if (index > 0) it.capitalize() else it }.joinToString("")
}