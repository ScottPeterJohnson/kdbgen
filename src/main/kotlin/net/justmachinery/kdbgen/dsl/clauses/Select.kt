package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.PostgresType

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

interface SelectSource<Value> {
	val selectSource : SelectSourceBase<Value>
}
sealed class SelectSourceBase<Value> : SelectSource<Value> {
	override val selectSource = this
}
//A result value constructable from a number of parameters of a table
data class DataClassSource<Value>(
		val constructorParameters : List<SelectSourceBase<*>>,
		val construct: (List<Any?>)->Value
) : SelectSourceBase<Value>()
//A raw column of a table
data class RawColumnSource<Value>(val name : String, val type : PostgresType) : SelectSourceBase<Value>()

data class ReturnValues<V>(val values : List<*>)
@Suppress("UNCHECKED_CAST")
interface ReturningStatementBuilder {
	fun addReturningValue(source : SelectSource<*>)

	private fun returningValues(vararg columns : SelectSource<*>): ReturnValues<*> {
		for(column in columns){
			addReturningValue(column)
		}
		return ReturnValues<Any>(columns.toList())
	}
	fun returningNothing(): ReturnValues<NoResult> = returningValues() as ReturnValues<NoResult>
	fun <V> returning(column: SelectSource<V>): ReturnValues<Result1<V>> = returningValues(column) as ReturnValues<Result1<V>>
	fun <V1, V2> returning(first: SelectSource<V1>, second: SelectSource<V2>): ReturnValues<Result2<V1, V2>> = returningValues(first, second) as ReturnValues<Result2<V1, V2>>
	fun <V1, V2, V3> returning(first: SelectSource<V1>, second: SelectSource<V2>, third : SelectSource<V3>): ReturnValues<Result3<V1, V2, V3>> = returningValues(first,second,third) as ReturnValues<Result3<V1, V2, V3>>
	fun <V1, V2, V3, V4> returning(first: SelectSource<V1>, second: SelectSource<V2>, third : SelectSource<V3>, fourth : SelectSource<V4>): ReturnValues<Result4<V1, V2, V3, V4>> = returningValues(first,second,third,fourth) as ReturnValues<Result4<V1, V2, V3, V4>>
}

interface SelectStatementBuilder : ReturningStatementBuilder, WhereStatementBuilder