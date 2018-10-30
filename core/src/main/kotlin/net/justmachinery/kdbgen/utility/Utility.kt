package net.justmachinery.kdbgen.utility

import net.justmachinery.kdbgen.commonTimestampFull
import net.justmachinery.kdbgen.commonUuidFull
import net.justmachinery.kdbgen.dsl.SqlScope
import net.justmachinery.kdbgen.dsl.clauses.ResultTuple
import net.justmachinery.kdbgen.dsl.clauses.Selectable
import org.postgresql.jdbc.PgArray
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.jvmErasure

internal fun <Result : ResultTuple> selectMapper(resultClass : KClass<Result>, scope: SqlScope, selects : List<Selectable<*>>, resultSet : ResultSet) : Result {
	fun parseSelect(select : Selectable<*>) : Any? {
		val mapped = scope.resolve(select)
		return select.construct(mapped.map { (alias, type) ->
			convertFromResultSet(resultSet.getObject(alias), type)
		})
	}
	val values = selects.map(::parseSelect).toTypedArray()
	return resultClass.primaryConstructor!!.call(*values)
}

private fun convertFromResultSet(value : Any?, type : KType) : Any?  {
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
		assert(type.isSubtypeOf(List::class.starProjectedType))
		assert(type.arguments.size == 1)

		val array = value.array as Array<*>
		result = array.toList().map { convertFromResultSet(it, type.arguments[0].type!!) }
	}
	if (result is String && notNullType.isSubtypeOf(Enum::class.starProjectedType)) {
		val typeClass = type.jvmErasure.java
		result = reflectionCreateEnum(typeClass, result)
	}
	if(result is Timestamp && notNullType.jvmErasure.qualifiedName == commonTimestampFull){
		result = notNullType.jvmErasure.primaryConstructor!!.call(result.time, result.nanos)
	}
	if(result is UUID && notNullType.jvmErasure.qualifiedName == commonUuidFull){
		result = notNullType.jvmErasure.primaryConstructor!!.call(result.mostSignificantBits, result.leastSignificantBits)
	}
	return result
}

private fun reflectionCreateEnum(clazz: Class<*>, value : String) : Any {
	return java.lang.Enum.valueOf(asEnumClass<TimeUnit>(clazz), value)
}
@Suppress("UNCHECKED_CAST")
private fun <T: Enum<T>> asEnumClass(clazz: Class<*>): Class<T> = clazz as Class<T>

