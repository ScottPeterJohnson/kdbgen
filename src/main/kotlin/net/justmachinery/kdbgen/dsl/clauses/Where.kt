package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.asParameter

interface WhereStatementBuilder {
	fun addWhereClause(sql : String, paramType : String, paramValue : Any?)

	fun where(where : WhereInit.()->Unit) {
		where(WhereInit(this))
	}
}

class WhereInit(private val builder : WhereStatementBuilder) {
	private fun <Value> TableColumn<Value>.columnClause(clause : String, values: Value){
		builder.addWhereClause(clause, this.rawType, values)
	}
	private fun <Value> TableColumn<Value>.opClause(op : String, value : Value){
		this.columnClause("${this.name} $op ${this.asParameter(value)}", value)
	}

	infix fun <Value> TableColumn<Value>.equalTo(value : Value) = opClause("=", value)
	infix fun <Value> TableColumn<Value>.notEqualTo(value : Value) = opClause("!=", value)
	infix fun <Value> TableColumn<Value>.greaterThan(value : Value) = opClause(">", value)
	infix fun <Value> TableColumn<Value>.greaterThanOrEqualTo(value : Value) = opClause(">=", value)
	infix fun <Value> TableColumn<Value>.lessThan(value : Value) = opClause("<", value)
	infix fun <Value> TableColumn<Value>.lessThanOrEqualTo(value : Value) = opClause("<=", value)
	infix fun <Value> TableColumn<Value>.within(values : List<Value>) = builder.addWhereClause("${this.name} = ANY(?)", this.rawType.plus("[]"), values)
}
