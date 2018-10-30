package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.*

interface UpdateStatementBuilder : CanHaveReturningValue, CanHaveWhereStatement, CanHaveJoins, UpdateStatementContext

interface UpdateStatementContext : SqlDslBase {
	fun <V> addUpdateValue(left : TableColumn<V>, right : Expression<out V>)

	infix fun <V> TableColumn<V>.setTo(other : Expression<out V>){
		addUpdateValue(this, other)
	}
}

data class SqlUpdateValue(val left : TableColumn<*>, val right : Expression<*>) {
	fun render(scope : SqlScope) : RenderedSqlFragment {
		return RenderedSqlFragment.build(scope) {
			add(left.name + " = ")
			add(right)
		}
	}
}