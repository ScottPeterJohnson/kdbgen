package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.commonTimestampFull
import net.justmachinery.kdbgen.commonTypesPackage
import net.justmachinery.kdbgen.commonUuidFull
import net.justmachinery.kdbgen.generation.utility.Json
import net.justmachinery.kdbgen.generation.utility.onlyWhen
import org.postgresql.geometric.*
import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PGInterval
import java.io.File
import java.math.BigDecimal
import java.nio.file.Paths
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.util.*



data class Settings(
	val databaseUrl : String,
	val outputDirectory : String,
	val dslOutputDirectory : String?,
	val useCommonTypes : Boolean,
	val enumPackage : String,
	val dataPackage : String,
	val dataAnnotation : List<String>,
	val mutableData : Boolean
) {

	private fun directory(directory: String, `package`: String) = Paths.get(directory,
		`package`.replace(".", "/")).toString()

	fun enumDirectory(): String = directory(outputDirectory, enumPackage)
	fun dataDirectory(): String = directory(outputDirectory, dataPackage)
	fun commonTypesDirectory(): String = directory(outputDirectory, commonTypesPackage)
	fun dslDirectory(): String = directory(dslOutputDirectory ?: outputDirectory, dataPackage)
}

fun runGeneration(settings: Settings) {
	File(settings.enumDirectory()).deleteRecursively()
	File(settings.dataDirectory()).deleteRecursively()
	File(settings.dslDirectory()).deleteRecursively()
	File(settings.commonTypesDirectory()).deleteRecursively()

	val (tables, context) = constructContext(settings)
	for(enum in context.postgresTypeToEnum.values){
		renderEnumType(context.settings, enum)
	}

	for (type in tables) {
		val renderer = DslRenderer(type, context)
		renderer.render()
	}

	if(settings.useCommonTypes){
		renderCommonTypes(settings)
	}
}


private fun constructContext(settings : Settings) : Pair<List<PostgresTable>, RenderingContext> {
	DriverManager.registerDriver(org.postgresql.Driver())
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

	return Pair(types, RenderingContext(settings, userEnumTypes))
}


internal class RenderingContext(
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

	internal data class TypeRepr(val base : String, val nullable : Boolean, val params : List<TypeRepr>)
	internal fun mapPostgresType(postgresType: String, nullable: Boolean): TypeRepr {
		val defaultType = when (postgresType) {
			//See TypeInfoCache in the Postgres driver implementation
			"int", "smallint", "integer", "int2", "int4" -> Integer::class
			"oid", "bigint", "int8", "bigserial", "serial8" -> Long::class
			"float", "float4" -> Float::class
			"money", "float8" -> Double::class
			"decimal", "numeric" -> BigDecimal::class
			"bit", "bool", "boolean" -> Boolean::class
			"char", "bpchar", "varchar", "text", "name", "inet" -> String::class

			"json", "jsonb" -> Json::class
			"date" -> java.sql.Date::class
			"time", "timetz" -> Time::class
			"timestamp", "timestamptz" -> if (!settings.useCommonTypes) Timestamp::class else null
			"uuid" -> if (!settings.useCommonTypes) UUID::class else null
			"bytea" -> ByteArray::class
			"refcursor" -> ResultSet::class
			"point" -> PGpoint::class
			"interval" -> PGInterval::class
			"polygon" -> PGpolygon::class
			"path" -> PGpath::class
			"lseg" -> PGlseg::class
			"line" -> PGline::class
			"circle" -> PGcircle::class
			"box" -> PGbox::class
			else -> null
		}
		if (defaultType != null) {
			return TypeRepr(defaultType.qualifiedName!!, nullable, emptyList())
		}
		if(postgresType == "timestamp" || postgresType == "timestamptz"){
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