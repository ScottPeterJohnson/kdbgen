package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Parameter
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.asParameter

interface WhereStatementBuilder {
	fun addJoinTable(table : Table<*>)
	fun addWhereClause(sql : String, parameters : List<Parameter>)

	fun where(where : WhereInit.()->Unit) {
		where(WhereInit(this))
	}
}

class WhereInit(private val builder : WhereStatementBuilder) {
	private fun <Value> TableColumn<Value>.opClause(op : String, value : Value){
		builder.addWhereClause("${this.name} $op ${this.asParameter()}", listOf(Parameter(this.rawType, value)))
	}
	private fun <Value> TableColumn<Value>.colClause(op : String, column : TableColumn<Value>){
		builder.addWhereClause("${this.name} $op ${column.name}", emptyList())
	}

	infix fun <Value> TableColumn<Value>.equalTo(column : TableColumn<Value>) = colClause("=", column)
	infix fun <Value> TableColumn<Value>.equalTo(value : Value) = opClause("=", value)
	infix fun <Value> TableColumn<Value>.notEqualTo(value : Value) = opClause("!=", value)
	infix fun <Value> TableColumn<Value>.greaterThan(value : Value) = opClause(">", value)
	infix fun <Value> TableColumn<Value>.greaterThanOrEqualTo(value : Value) = opClause(">=", value)
	infix fun <Value> TableColumn<Value>.lessThan(value : Value) = opClause("<", value)
	infix fun <Value> TableColumn<Value>.lessThanOrEqualTo(value : Value) = opClause("<=", value)
	infix fun <Value> TableColumn<Value>.within(values : List<Value>) = builder.addWhereClause("${this.name} = ANY(?)", listOf(Parameter(this.rawType.plus("[]"), values)))
}
