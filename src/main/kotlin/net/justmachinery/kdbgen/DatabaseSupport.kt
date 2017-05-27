package net.justmachinery.kdbgen

import org.postgresql.util.PGobject
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.jvmErasure

interface Operation<Result> {
	val sql : String
	val parameters : Map<String,Any?>
}
data class SelectOperation<Result>(override val sql : String, override val parameters : Map<String,Any?>) : Operation<Result>
data class InsertOperation<Result>(override val sql : String, override val parameters : Map<String,Any?>) : Operation<Result>
data class DeleteOperation<Result>(override val sql : String, override val parameters : Map<String,Any?>) : Operation<Result>
data class UpdateOperation<Result>(override val sql : String, override val parameters : Map<String,Any?>) : Operation<Result>

fun <Data : Any> dataClassMapper(dataClass: KClass<Data>): (java.sql.ResultSet) -> Data {
	val constructor = dataClass.primaryConstructor!!
	val parameters = constructor.parameters.map { Pair(it, underscore(it.name!!)) }

	return {
		val parameterMap = mutableMapOf<KParameter, Any?>()
		for ((parameter, sqlName) in parameters) {
			var value: Any? = it.getObject(sqlName)
			if (value is PGobject) {
				value = value.value
			}
			if(value is String && parameter.type.isSubtypeOf(Enum::class.starProjectedType)){
				val typeClass = parameter.type.jvmErasure.java
				value = reflectionCreateEnum(typeClass, value)
			}
			parameterMap[parameter] = value
		}
		constructor.callBy(parameterMap)
	}
}

fun underscore(name: String): String {
	return name.mapIndexed { index, char -> if (index != 0 && char.isUpperCase()) "_" + char else char.toString() }.joinToString(
			"")
}

fun reflectionCreateEnum(clazz: Class<*>, value : String) : Any {
	return java.lang.Enum.valueOf(asEnumClass<TimeUnit>(clazz), value)
}
@Suppress("UNCHECKED_CAST")
fun <T: Enum<T>> asEnumClass(clazz: Class<*>): Class<T> = clazz as Class<T>