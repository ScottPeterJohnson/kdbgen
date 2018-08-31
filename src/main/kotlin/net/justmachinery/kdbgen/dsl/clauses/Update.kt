package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.TableColumn

interface UpdateStatementBuilder : ReturningStatementBuilder, WhereStatementBuilder {
	fun addUpdateValue(value : Pair<TableColumn<*>, *>)

	infix fun <V> TableColumn<V>.setTo(value : V){
		addUpdateValue(Pair(this, value))
	}
}