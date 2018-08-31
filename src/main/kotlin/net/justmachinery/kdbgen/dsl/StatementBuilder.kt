package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.*
import kotlin.reflect.KClass


data class StatementBuilder (
		val table : Table,
		var parameterCount : Int = 0,
		val selectValues : MutableList<SelectSource<*>> = mutableListOf(),
		val updateValues: MutableList<Pair<TableColumn<*>, *>> = mutableListOf(),
		val insertValues : MutableList<List<Pair<TableColumn<*>, *>>> = mutableListOf(),
		val whereClauses : MutableList<WhereClause> = mutableListOf(),
		var isDelete : Boolean = false
) : WhereStatementBuilder, UpdateStatementBuilder, ReturningStatementBuilder, InsertStatementBuilder, DeleteStatementBuilder {
	override fun addWhereClause(sql : String, paramType : String, paramValue : Any?){
		whereClauses += WhereClause(sql, paramType, paramValue)
	}
	override fun addUpdateValue(value : Pair<TableColumn<*>, *>){
		updateValues.add(value)
	}
	override fun addInsertValues(values : List<Pair<TableColumn<*>, *>>){
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

data class WhereClause(val sql : String, val postgresType : String, val paramValue : Any?)

internal enum class SqlOperation {
	SELECT,
	INSERT,
	UPDATE,
	DELETE
}

inline fun <Q : Table, reified Result : ResultTuple> Q.statement(cb : StatementBuilder.()->ReturnValues<Result>) : StatementReturning<Result> {
	val builder = StatementBuilder(this)
	cb(builder)
	return StatementReturning(builder, Result::class)
}

data class StatementReturning<Result : ResultTuple>(val builder : StatementBuilder, val resultClass : KClass<Result>)