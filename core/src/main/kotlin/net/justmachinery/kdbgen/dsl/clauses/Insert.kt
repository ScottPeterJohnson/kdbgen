package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Expression
import net.justmachinery.kdbgen.dsl.SqlDslBase
import net.justmachinery.kdbgen.dsl.TableColumn

interface InsertStatementBuilder : CanHaveReturningValue, SqlDslBase {
	fun addInsertValues(values : List<SqlInsertValue<*>>)
	fun addConflictClause(clause : OnConflictClause)
}

data class SqlInsertValue<T>(val column : TableColumn<T>, val value : Expression<T>)


fun InsertStatementBuilder.onConflictDoNothing(vararg columns : TableColumn<*>){
	addConflictClause(OnConflictClause(columns.toList(), emptyList()))
}

data class OnConflictClause(val columns : List<TableColumn<*>>, val updates : List<SqlUpdateValue>)


data class ConflictUpdateBuilder(val updates : MutableList<SqlUpdateValue> = mutableListOf()) : UpdateStatementContext {
	override fun <V> addUpdateValue(left: TableColumn<V>, right: Expression<out V>) {
		updates.add(SqlUpdateValue(left, right))
	}
}