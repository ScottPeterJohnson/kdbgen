package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.SqlClauseValue
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn

interface WhereStatementBuilder {
	fun addJoinTable(table : Table<*>)
	fun <T> addWhereClause(left : SqlClauseValue<T>, op : String, right : SqlClauseValue<T>)

	fun where(where : WhereInit.()->Unit) {
		where(WhereInit(this))
	}
}

class WhereInit(private val builder : WhereStatementBuilder) {
	private fun <Value> TableColumn<Value>.opClause(op : String, value : Value){
		builder.addWhereClause(SqlClauseValue.Column(this), op, SqlClauseValue.Value(value, this.type))
	}
	private fun <Value> TableColumn<Value>.colClause(op : String, column : TableColumn<Value>){
		builder.addWhereClause(SqlClauseValue.Column(this), op, SqlClauseValue.Column(column))
	}

	infix fun <Value> TableColumn<Value>.equalTo(column : TableColumn<Value>) = colClause("=", column)
	infix fun <Value> TableColumn<Value>.equalTo(value : Value) = opClause("=", value)
	infix fun <Value> TableColumn<Value>.notEqualTo(value : Value) = opClause("!=", value)
	infix fun <Value> TableColumn<Value>.greaterThan(value : Value) = opClause(">", value)
	infix fun <Value> TableColumn<Value>.greaterThanOrEqualTo(value : Value) = opClause(">=", value)
	infix fun <Value> TableColumn<Value>.lessThan(value : Value) = opClause("<", value)
	infix fun <Value> TableColumn<Value>.lessThanOrEqualTo(value : Value) = opClause("<=", value)
	infix fun <Value> TableColumn<Value>.within(values : List<Value>) = builder.addWhereClause(
			SqlClauseValue.Column(this),
			"=",
			SqlClauseValue.FunctionCall("ANY", listOf(SqlClauseValue.Value(values, this.type.toArray()))))
}
