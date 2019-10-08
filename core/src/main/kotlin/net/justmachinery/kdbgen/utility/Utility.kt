package net.justmachinery.kdbgen.utility

import org.postgresql.jdbc.PgArray
import org.postgresql.util.PGobject
import java.sql.Connection
import java.util.concurrent.TimeUnit
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.jvmErasure

fun convertToParameterType(value : Any?, postgresType : String, connection: Connection): Any? {
	when (value) {
		is List<*> -> {
			val subtype = postgresType.removeSuffix("[]").removePrefix("_")
			return connection.createArrayOf(
				subtype,
				value.map {
					convertToParameterType(it, subtype, connection)
				}.toTypedArray()
			)
		}
		is Enum<*> -> return value.toString()
	}
	return value
}

fun convertFromResultSetObject(value : Any?, type : KType) : Any?  {
	var result : Any? = value
	if (value is PGobject) {
		result = value.value
	}
	if(result == null){
		assert(type.isMarkedNullable)
		return null
	}
	val notNullType = type.withNullability(false)

	if(value is PgArray){
		assert(notNullType.isSubtypeOf(List::class.starProjectedType))
		assert(type.arguments.size == 1)

		val array = value.array as Array<*>
		result = array.toList().map { convertFromResultSetObject(it, type.arguments[0].type!!) }
	}
	if (result is String && notNullType.isSubtypeOf(Enum::class.starProjectedType)) {
		val typeClass = type.jvmErasure.java
		result = reflectionCreateEnum(typeClass, result)
	}
	return result
}


private fun reflectionCreateEnum(clazz: Class<*>, value : String) : Any {
	return java.lang.Enum.valueOf(asEnumClass<TimeUnit>(clazz), value)
}
@Suppress("UNCHECKED_CAST")
private fun <T: Enum<T>> asEnumClass(clazz: Class<*>): Class<T> = clazz as Class<T>
