package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Expression
import net.justmachinery.kdbgen.dsl.RenderedSqlFragment
import net.justmachinery.kdbgen.dsl.SqlScope
import net.justmachinery.kdbgen.dsl.TableColumn


interface CanHaveWhereStatement {
	fun <Value, V2 : Value> addWhereClause(left : Expression<Value>, op : String, right : Expression<in V2>)


	fun where(where : WhereInit.()->Unit) {
		where(WhereInit(this))
	}
}
class WhereInit(private val builder : CanHaveWhereStatement) {
	fun <Value, V2 : Value> Expression<Value>.op(op : String, value : Expression<in V2>){
		builder.addWhereClause(this, op, value)
	}

	infix fun <Value, V2 : Value> Expression<Value>.equalTo(value : Expression<in V2>) = op("=", value)
	infix fun <Value, V2 : Value> Expression<Value>.notEqualTo(value : Expression<in V2>) = op("!=", value)
	infix fun <Value, V2 : Value> Expression<Value>.greaterThan(value : Expression<in V2>) = op(">", value)
	infix fun <Value, V2 : Value> Expression<Value>.greaterThanOrEqualTo(value : Expression<in V2>) = op(">=", value)
	infix fun <Value, V2 : Value> Expression<Value>.lessThan(value : Expression<in V2>) = op("<", value)
	infix fun <Value, V2 : Value> Expression<Value>.lessThanOrEqualTo(value : Expression<in V2>) = op("<=", value)
	inline infix fun <Value, reified V2 : Value> TableColumn<Value>.within(values : List<V2>) {
		op("=", Expression.callFunction<Value>("ANY", Expression.parameter(values, this.type)))
	}
}

data class WhereClause(val left : Expression<*>, val op : String, val right : Expression<*>) {
	fun render(scope : SqlScope) : RenderedSqlFragment {
		return RenderedSqlFragment.build(scope) {
			add(left)
			add(" $op ")
			add(right)
		}
	}
}