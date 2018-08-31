package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.StatementReturning
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.statement

interface UpdateStatementBuilder : ReturningStatementBuilder {
	fun addUpdateValue(value : Pair<TableColumn<*>, *>)

	infix fun <V> TableColumn<V>.setTo(value : V){
		addUpdateValue(Pair(this, value))
	}
}