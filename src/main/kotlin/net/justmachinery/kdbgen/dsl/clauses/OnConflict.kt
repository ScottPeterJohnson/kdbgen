package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.SqlClauseValue
import net.justmachinery.kdbgen.dsl.SqlUpdateValue
import net.justmachinery.kdbgen.dsl.TableColumn


data class OnConflictClause(val columns : List<TableColumn<*>>, val updates : List<SqlUpdateValue<*>>)
fun InsertStatementBuilder.onConflictDoNothing(vararg columns : TableColumn<*>){
	addConflictClause(OnConflictClause(columns.toList(), emptyList()))
}


data class ConflictUpdateBuilder(val updates : MutableList<SqlUpdateValue<*>> = mutableListOf()) : UpdateStatementContext {
	override fun <T> addUpdateValue(left: TableColumn<T>, right: SqlClauseValue<T>){
		updates.add(SqlUpdateValue(left, right))
	}
}