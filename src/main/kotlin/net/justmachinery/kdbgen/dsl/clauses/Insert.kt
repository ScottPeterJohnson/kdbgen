package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.StatementReturning
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.TableColumn
import net.justmachinery.kdbgen.dsl.statement

fun <T : Table> T.insert(cb: InsertStatementBuilder.() -> Unit): StatementReturning<Unit> =
		statement {
			cb()
		}
fun <T : Table, V> T.insertReturning(cb: InsertStatementBuilder.() -> ReturnValues<V>): StatementReturning<V> =
		statement {
			cb()
		}

interface InsertStatementBuilder : ReturningStatementBuilder {
	fun addInsertValues(values : List<Pair<TableColumn<*>, *>>)
}
