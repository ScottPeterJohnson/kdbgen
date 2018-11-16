package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.*


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


	class LikeInit(val tokens : MutableList<Expression<String>>) {
		fun anyCharacter(){
			tokens.add(sqlDsl.literal("_"))
		}
		fun anyString(){
			tokens.add(sqlDsl.literal("%"))
		}
		fun exact(value : Expression<String>){
			fun Expression<String>.repl(char : Char) : Expression<String> {
				return sqlDsl.callFunction(
					"replace",
					this,
					sqlDsl.literal("$char"),
					sqlDsl.literal("\\$char")
				)
			}
			tokens.add(value.repl('%').repl('_'))
		}
		fun prefix(value : Expression<String>){
			exact(value)
			anyString()
		}
		fun suffix(value : Expression<String>){
			anyString()
			exact(value)
		}

		fun contains(value : Expression<String>){
			anyString()
			exact(value)
			anyString()
		}
	}
	private fun buildLike(cb : (LikeInit)->Unit) : Expression<String> {
		val tokens = mutableListOf<Expression<String>>()
		val init = LikeInit(tokens)
		cb(init)
		return sqlDsl.callFunction("concat", *tokens.toTypedArray())
	}
	infix fun Expression<String>.like(cb : LikeInit.()->Unit) = op("LIKE", buildLike(cb))
	infix fun Expression<String>.ilike(cb : LikeInit.()->Unit) = op("ILIKE", buildLike(cb))
	infix fun Expression<String>.like(patternLiteral : String) = op("LIKE", sqlDsl.literal(patternLiteral))
	infix fun Expression<String>.ilike(patternLiteral : String) = op("ILIKE", sqlDsl.literal(patternLiteral))


	inline infix fun <Value, reified V2 : Value> TableColumn<Value>.within(values : List<V2>) {
		op("=", sqlDsl.callFunction<Value>("ANY", sqlDsl.parameter(values, this.type)))
	}
	inline infix fun <Value, reified V2 : Value> TableColumn<Value>.within(values : Expression<List<V2>>) {
		op("=", sqlDsl.callFunction<Value>("ANY", values))
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