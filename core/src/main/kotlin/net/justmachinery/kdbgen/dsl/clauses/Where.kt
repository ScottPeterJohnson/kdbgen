package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Expression
import net.justmachinery.kdbgen.dsl.RenderedSqlFragment
import net.justmachinery.kdbgen.dsl.SqlScope
import net.justmachinery.kdbgen.dsl.TableColumn


interface CanHaveWhereStatement {
	fun addWhereClause(clause : WhereClause)


	fun where(where : WhereInit.()->Unit) {
		where(WhereInit(this))
	}
}

private class WhereStatementBuilder : CanHaveWhereStatement {
	val clauses = mutableListOf<WhereClause>()
	override fun addWhereClause(clause: WhereClause) {
		clauses.add(clause)
	}
}

class WhereInit(private val builder : CanHaveWhereStatement) {
	fun <Value, V2 : Value> Expression<Value>.op(op : String, value : Expression<in V2>){
		builder.addWhereClause(OpWhereClause(this, op, value))
	}

	fun <Value> Expression<Value?>.isNull() = builder.addWhereClause(IsClause(this, addNot = false))
	fun <Value> Expression<Value?>.isNotNull() = builder.addWhereClause(IsClause(this, addNot = true))

	infix fun <Value, V2 : Value> Expression<Value>.equalTo(value : Expression<in V2>) = op("=", value)
	infix fun <Value, V2 : Value> Expression<Value>.notEqualTo(value : Expression<in V2>) = op("!=", value)
	infix fun <Value, V2 : Value> Expression<Value>.greaterThan(value : Expression<in V2>) = op(">", value)
	infix fun <Value, V2 : Value> Expression<Value>.greaterThanOrEqualTo(value : Expression<in V2>) = op(">=", value)
	infix fun <Value, V2 : Value> Expression<Value>.lessThan(value : Expression<in V2>) = op("<", value)
	infix fun <Value, V2 : Value> Expression<Value>.lessThanOrEqualTo(value : Expression<in V2>) = op("<=", value)
	inline infix fun <Value, reified V2 : Value> TableColumn<Value>.within(values : List<V2>) {
		op("=", Expression.callFunction<Value>("ANY", Expression.parameter(values, this.type)))
	}

	private fun conjoined(joiner : String, cb : WhereInit.()->Unit) {
		val subBuilder = WhereStatementBuilder()
		cb(WhereInit(subBuilder))
		builder.addWhereClause(ConjoinedWhereClauses(joiner, subBuilder.clauses))
	}

	fun anyOf(cb : WhereInit.()->Unit) = conjoined(" OR ", cb)

	fun allOf(cb : WhereInit.()->Unit) = conjoined(" AND ", cb)
}

interface WhereClause : Expression<Boolean>
private data class OpWhereClause(val left : Expression<*>, val op : String, val right : Expression<*>) : WhereClause  {
	override fun render(scope : SqlScope) : RenderedSqlFragment {
		return RenderedSqlFragment.build(scope) {
			add(left)
			add(" $op ")
			add(right)
		}
	}
}

private data class ConjoinedWhereClauses(val joiner : String, val clauses : List<WhereClause>) : WhereClause {
	override fun render(scope: SqlScope): RenderedSqlFragment {
		return RenderedSqlFragment.build(scope){
			add("(")
			addJoinedExprs(joiner, clauses)
			add(")")
		}
	}
}

private data class IsClause(val operand : Expression<*>, val addNot : Boolean) : WhereClause {
	override fun render(scope: SqlScope): RenderedSqlFragment {
		return RenderedSqlFragment.build(scope){
			add(operand)
			add(" IS ")
			if(addNot){
				add("NOT ")
			}
			add("NULL")
		}
	}
}