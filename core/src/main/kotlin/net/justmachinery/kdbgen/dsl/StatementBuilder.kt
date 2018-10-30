package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.*
import kotlin.reflect.KClass


data class StatementBuilder (
	val table : Table<*>
) : UpdateStatementBuilder, SelectStatementBuilder, InsertStatementBuilder, DeleteStatementBuilder {
	internal val selectValues : MutableList<Selectable<*>> = mutableListOf()
	internal var selectForUpdate : Boolean = false
	internal var selectSkipLocked : Boolean = false
	internal var selectLimit : Long? = null

	internal val updateValues: MutableList<SqlUpdateValue> = mutableListOf()
	internal val insertValues : MutableList<List<SqlInsertValue<*>>> = mutableListOf()
	internal var conflictClause: OnConflictClause? = null
	internal val whereClauses : MutableList<WhereClause> = mutableListOf()
	internal val joinTables : MutableList<Table<*>> = mutableListOf()
	var isDelete : Boolean = false

	override fun addConflictClause(clause: OnConflictClause) {
		conflictClause = clause
	}

	override fun addJoinTable(table: Table<*>) {
		joinTables.add(table)
	}

	override fun <Value, V2 : Value> addWhereClause(left: Expression<Value>, op: String, right: Expression<in V2>) {
		whereClauses += WhereClause(left, op, right)
	}

	override fun <V> addUpdateValue(left: TableColumn<V>, right: Expression<out V>) {
		updateValues.add(SqlUpdateValue(left, right))
	}

	override fun addInsertValues(values : List<SqlInsertValue<*>>){
		insertValues.add(values)
	}
	override fun addReturningValue(source : Selectable<*>){
		selectValues.add(source)
	}

	override fun limit(amount: Long) {
		selectLimit = amount
	}

	override fun skipLocked() {
		selectSkipLocked = true
	}

	override fun forUpdate() {
		selectForUpdate = true
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