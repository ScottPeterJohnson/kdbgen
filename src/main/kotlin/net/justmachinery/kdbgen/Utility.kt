package net.justmachinery.kdbgen

import org.postgresql.jdbc.PgArray
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

typealias Json = String

/**
 * Attempt to construct a data class instance from a result set, mapping parameters based on propertyCase -> property_case
 * Some type casting may be done as necessary.
 */
fun <Data : Any> resultMapper(dataClass: KClass<Data>): (ResultSet) -> Data {
	val constructor = dataClass.primaryConstructor!!
	val parameters = constructor.parameters.map { Pair(it, underscore(it.name!!)) }

	if(dataClass.isSubclassOf(ResultTuple::class)){
		return {
			constructor.call(*parameters.mapIndexed { index, (parameter, _) ->
				//Remember: JDBC resultsets start at 1 because... reasons
				convertFromResultSet(it.getObject(index + 1), parameter.type)
			}.toTypedArray())
		}
	} else {
		//Data class
		return {
			val parameterMap = mutableMapOf<KParameter, Any?>()
			for ((parameter, sqlName) in parameters) {
				parameterMap[parameter] = convertFromResultSet(it.getObject(sqlName), parameter.type)
			}
			constructor.callBy(parameterMap)
		}
	}
}

private fun convertFromResultSet(value : Any?, type : KType) : Any?  {
	var result : Any? = value
	if (value is PGobject) {
		result = value.value
	}
	if(value is PgArray){
		val arrayType : KType
		if(type.arguments.isNotEmpty()) { arrayType = type.arguments[0].type ?: Object::class.starProjectedType }
		else { arrayType = Object::class.starProjectedType }
		val array = value.array as Array<*>
		result = array.toList().map { convertFromResultSet(it, arrayType) }
	}
	if (result is String && type.withNullability(false).isSubtypeOf(Enum::class.starProjectedType)) {
		val typeClass = type.jvmErasure.java
		result = reflectionCreateEnum(typeClass, result)
	}
	if(result is Timestamp && type.isSubtypeOf(Long::class.starProjectedType)){
		result = result.time
	}
	if(result is UUID && type.isSubtypeOf(String::class.starProjectedType)){
		result = result.toString()
	}
	return result
}

fun underscore(name: String): String {
	return name.mapIndexed { index, char ->
		if (index != 0 && char.isUpperCase()) "_$char" else char.toString()
	}.joinToString("")
}

fun reflectionCreateEnum(clazz: Class<*>, value : String) : Any {
	return java.lang.Enum.valueOf(asEnumClass<TimeUnit>(clazz), value)
}
@Suppress("UNCHECKED_CAST")
fun <T: Enum<T>> asEnumClass(clazz: Class<*>): Class<T> = clazz as Class<T>
