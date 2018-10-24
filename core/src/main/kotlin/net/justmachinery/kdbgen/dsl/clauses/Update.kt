package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.SqlClauseValue
import net.justmachinery.kdbgen.dsl.TableColumn

interface UpdateStatementBuilder : ReturningStatementBuilder, WhereStatementBuilder, UpdateStatementContext {
}

interface UpdateStatementContext {
	fun <T> addUpdateValue(left : TableColumn<T>, right : SqlClauseValue<T>)

	infix fun <V> TableColumn<V>.setTo(value : V){
		addUpdateValue(this, SqlClauseValue.Value(value, this.type))
	}
	infix fun <V> TableColumn<V>.setTo(other : TableColumn<V>){
		addUpdateValue(this, SqlClauseValue.Column(other))
	}
}