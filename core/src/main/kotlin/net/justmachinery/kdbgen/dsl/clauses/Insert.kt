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
	addConflictClause(OnConflictClause(columns.toList(), emptyList(), emptyList()))
}

data class OnConflictClause(
	val columns : List<TableColumn<*>>,
	val updates : List<SqlUpdateValue>,
	val whereClauses : List<WhereClause>
)


class ConflictUpdateBuilder : UpdateStatementContext, CanHaveWhereStatement {
	private val updates= mutableListOf<SqlUpdateValue>()
	private val whereClauses = mutableListOf<WhereClause>()

	override fun addWhereClause(clause : WhereClause) {
		whereClauses.add(clause)
	}

	override fun <V> addUpdateValue(left: TableColumn<V>, right: Expression<out V>) {
		updates.add(SqlUpdateValue(left, right))
	}

	fun build(columns : List<TableColumn<*>>) : OnConflictClause {
		return OnConflictClause(columns, updates, whereClauses)
	}
}