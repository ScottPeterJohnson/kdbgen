package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Expression
import net.justmachinery.kdbgen.dsl.PostgresType
import net.justmachinery.kdbgen.dsl.SqlDslBase

/**
 * Result data tuples
 */
interface ResultTuple
class NoResult : ResultTuple
data class Result1<out V1>(val first : V1) : ResultTuple
data class Result2<out V1, out V2>(val first : V1, val second : V2) : ResultTuple
data class Result3<out V1, out V2, out V3>(val first : V1, val second : V2, val third : V3) : ResultTuple
data class Result4<out V1, out V2, out V3, out V4>(val first : V1, val second : V2, val third : V3, val fourth : V4) : ResultTuple
data class Result5<out V1, out V2, out V3, out V4, out V5>(val first : V1, val second : V2, val third : V3, val fourth : V4, val fifth : V5) : ResultTuple
data class Result6<out V1, out V2, out V3, out V4, out V5, out V6>(val first : V1, val second : V2, val third : V3, val fourth : V4, val fifth : V5, val sixth : V6) : ResultTuple

/**
 * An expression with a reified type
 */
data class TypedExpression<T>(val expression : Expression<T>, val type : PostgresType)

interface Selectable<out Value> {
	fun toExpressions() : List<TypedExpression<*>>
	fun construct(values : List<Any?>) : Value
}

//A result value constructable from a number of parameters of a table
data class DataClassSource<Value>(
		val constructorParameters : List<Selectable<*>>,
		val constructFunction: (List<Any?>)->Value
) : Selectable<Value> {
	override fun construct(values: List<Any?>): Value = constructFunction(values)

	override fun toExpressions(): List<TypedExpression<*>> {
		return constructorParameters.flatMap { it.toExpressions() }
	}
}


data class ReturnValues<V>(val values : List<*>)
@Suppress("UNCHECKED_CAST")
interface CanHaveReturningValue {
	fun addReturningValue(source : Selectable<*>)

	private fun returningValues(vararg selects : Selectable<*>): ReturnValues<*> {
		for(select in selects){
			addReturningValue(select)
		}
		return ReturnValues<Any>(selects.toList())
	}
	fun returningNothing(): ReturnValues<NoResult> = returningValues() as ReturnValues<NoResult>
	fun <V> returning(only: Selectable<V>): ReturnValues<Result1<V>> = returningValues(only) as ReturnValues<Result1<V>>
	fun <V1, V2> returning(first: Selectable<V1>, second: Selectable<V2>): ReturnValues<Result2<V1, V2>> = returningValues(first, second) as ReturnValues<Result2<V1, V2>>
	fun <V1, V2, V3> returning(first: Selectable<V1>, second: Selectable<V2>, third : Selectable<V3>): ReturnValues<Result3<V1, V2, V3>> = returningValues(first,second,third) as ReturnValues<Result3<V1, V2, V3>>
	fun <V1, V2, V3, V4> returning(first: Selectable<V1>, second: Selectable<V2>, third : Selectable<V3>, fourth : Selectable<V4>): ReturnValues<Result4<V1, V2, V3, V4>> = returningValues(first,second,third,fourth) as ReturnValues<Result4<V1, V2, V3, V4>>
}

interface SelectStatementBuilder : CanHaveReturningValue, CanHaveWhereStatement, CanHaveJoins, SqlDslBase {
	fun limit(amount : Long)
	fun skipLocked()
	fun forUpdate()
}