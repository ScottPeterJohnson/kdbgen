package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.SqlInsertValue


interface InsertStatementBuilder : ReturningStatementBuilder {
	fun addInsertValues(values : List<SqlInsertValue<*>>)
	fun addConflictClause(clause : OnConflictClause)
}
