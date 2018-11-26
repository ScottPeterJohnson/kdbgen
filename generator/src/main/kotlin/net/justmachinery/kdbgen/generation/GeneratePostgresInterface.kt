package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.generation.utility.Json
import org.postgresql.geometric.*
import org.postgresql.util.PGInterval
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.util.*



data class Settings(
	val databaseUrl : String,
	val outputDirectory : String
)

internal class RenderingContext(
	val settings: Settings,
	enums: List<EnumType>) {
	internal val postgresTypeToEnum = enums.associateBy { it.postgresName }

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
			"timestamp", "timestamptz" -> Timestamp::class
			"uuid" -> UUID::class
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

		if (postgresTypeToEnum.containsKey(postgresType)) {
			return TypeRepr(
					"net.justmachinery.kdbgen.sql.${postgresTypeToEnum[postgresType]!!.className}",
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

//Format is TypeName
internal fun underscoreToCamelCaseTypeName(name: String): String {
	return name.split("_").map(String::capitalize).joinToString("")
}

//Format is memberName
internal fun underscoreToCamelCaseMemberName(name: String): String {
	return name.split("_").mapIndexed { index, it -> if (index > 0) it.capitalize() else it }.joinToString("")
}