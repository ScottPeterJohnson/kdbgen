package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.StatementReturning
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.statement


interface InsertStatementBuilder : ReturningStatementBuilder {
	fun addInsertValues(values : List<Pair<TableColumn<*>, *>>)
}
