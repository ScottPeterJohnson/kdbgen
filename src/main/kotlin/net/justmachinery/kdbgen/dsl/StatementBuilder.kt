package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.*
import kotlin.reflect.KClass


data class StatementBuilder (
		val table : Table<*>,
		var parameterCount : Int = 0,
		val selectValues : MutableList<SelectSource<*>> = mutableListOf(),
		val updateValues: MutableList<SqlUpdateValue<*>> = mutableListOf(),
		val insertValues : MutableList<List<SqlInsertValue<*>>> = mutableListOf(),
		val whereClauses : MutableList<WhereClause<*>> = mutableListOf(),
		val joinTables : MutableList<Table<*>> = mutableListOf(),
		var isDelete : Boolean = false
) : UpdateStatementBuilder, SelectStatementBuilder, InsertStatementBuilder, DeleteStatementBuilder {
	override fun addJoinTable(table: Table<*>) {
		joinTables.add(table)
	}

	override fun <T> addWhereClause(left : SqlClauseValue<T>, op : String, right : SqlClauseValue<T>){
		whereClauses += WhereClause(left, op, right)
	}
	override fun <T> addUpdateValue(left : TableColumn<T>, right : SqlClauseValue<T>){
		updateValues.add(SqlUpdateValue(left, right))
	}
	override fun addInsertValues(values : List<SqlInsertValue<*>>){
		insertValues.add(values)
	}
	override fun addReturningValue(source : SelectSource<*>){
		selectValues.add(source)
	}

	internal fun operation() : SqlOperation {
		val isUpdate = updateValues.isNotEmpty()
		val isInsert = insertValues.isNotEmpty()

		return if(isUpdate || isInsert){
			assert(!(isUpdate && isInsert))
			assert(!isDelete)
			if(isUpdate){
				SqlOperation.UPDATE
			} else {
				SqlOperation.INSERT
			}
		} else {
			if(isDelete){
				SqlOperation.DELETE
			} else {
				SqlOperation.SELECT
			}
		}
	}
}


sealed class SqlClauseValue<T> {
	abstract fun render() : String
	abstract fun parameters() : List<SqlClauseValue.Value<*>>

	data class Value<T>(val value : T, val type: PostgresType) : SqlClauseValue<T>() {
		override fun parameters(): List<Value<*>> {
			return listOf(this)
		}

		override fun render(): String {
			return type.asParameter()
		}
	}
	data class Column<T>(val column : TableColumn<T>) : SqlClauseValue<T>() {
		override fun parameters(): List<Value<*>> {
			return emptyList()
		}

		override fun render(): String {
			return column.name
		}
	}
	data class FunctionCall<Returning>(val name : String, val arguments: List<SqlClauseValue<*>>) : SqlClauseValue<Returning>() {
		override fun parameters(): List<Value<*>> {
			return arguments.flatMap { it.parameters() }
		}

		override fun render(): String {
			return name + "(" + arguments.joinToString(","){ it.render() } + ")"
		}
	}
}
data class SqlUpdateValue<T>(val left : TableColumn<T>, val right : SqlClauseValue<T>)

data class SqlInsertValue<T>(val column : TableColumn<T>, val value : T)

data class WhereClause<T>(val left : SqlClauseValue<T>, val op : String, val right : SqlClauseValue<T>) {
	fun render() : String {
		return left.render() + " $op " + right.render()
	}
}

internal enum class SqlOperation {
	SELECT,
	INSERT,
	UPDATE,
	DELETE
}

inline fun <Q : Table<*>, reified Result : ResultTuple> Q.statement(cb : StatementBuilder.()->ReturnValues<Result>) : StatementReturning<Result> {
	val builder = StatementBuilder(this)
	cb(builder)
	return StatementReturning(builder, Result::class)
}

data class StatementReturning<Result : ResultTuple>(val builder : StatementBuilder, val resultClass : KClass<Result>)