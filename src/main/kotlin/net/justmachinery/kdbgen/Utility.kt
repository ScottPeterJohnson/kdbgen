package net.justmachinery.kdbgen

import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

typealias Json = String

fun <Data : SqlResult> resultMapper(dataClass: KClass<Data>): (ResultSet) -> Data {
	val constructor = dataClass.primaryConstructor!!
	val parameters = constructor.parameters.map { Pair(it, underscore(it.name!!)) }

	if(dataClass.isSubclassOf(ResultTuple::class)){
		return {
			constructor.call(*parameters.mapIndexed { index, (parameter, _) ->
				//Remember: JDBC resultsets start at 1 because... reasons
				convertFromResultSet(it.getObject(index + 1), parameter)
			}.toTypedArray())
		}
	} else {
		//Data class
		return {
			val parameterMap = mutableMapOf<KParameter, Any?>()
			for ((parameter, sqlName) in parameters) {
				parameterMap[parameter] = convertFromResultSet(it.getObject(sqlName), parameter)
			}
			constructor.callBy(parameterMap)
		}
	}
}

private fun convertFromResultSet(value : Any?, parameter : KParameter) : Any?  {
	var result : Any? = value
	if (value is PGobject) {
		result = value.value
	}
	if (result is String && parameter.type.withNullability(false).isSubtypeOf(Enum::class.starProjectedType)) {
		val typeClass = parameter.type.jvmErasure.java
		result = reflectionCreateEnum(typeClass, result)
	}
	return result
}

fun underscore(name: String): String {
	return name.mapIndexed { index, char ->
		if (index != 0 && char.isUpperCase()) "_" + char else char.toString()
	}.joinToString("")
}

fun reflectionCreateEnum(clazz: Class<*>, value : String) : Any {
	return java.lang.Enum.valueOf(asEnumClass<TimeUnit>(clazz), value)
}
@Suppress("UNCHECKED_CAST")
fun <T: Enum<T>> asEnumClass(clazz: Class<*>): Class<T> = clazz as Class<T>
