package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.StatementReturning
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.statement

fun <T : Table> T.update(cb: UpdateStatementBuilder.() -> Unit): StatementReturning<Unit> =
		statement {
			cb()
		}
fun <T : Table, V> T.updateReturning(cb: UpdateStatementBuilder.() -> ReturnValues<V>): StatementReturning<V> =
		statement {
			cb()
		}


interface UpdateStatementBuilder : ReturningStatementBuilder {
	fun addUpdateValue(value : Pair<TableColumn<*>, *>)

	infix fun <V> TableColumn<V>.setTo(value : V){
		addUpdateValue(Pair(this, value))
	}
}