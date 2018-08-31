package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.StatementReturning
import net.justmachinery.kdbgen.dsl.Table
import net.justmachinery.kdbgen.dsl.statement

fun <T : Table> T.delete(cb: ReturningStatementBuilder.() -> Unit): StatementReturning<Unit> =
		statement {
			isDelete = true
			cb()
		}

fun <T : Table, V> T.deleteReturning(cb: ReturningStatementBuilder.() -> ReturnValues<V>): StatementReturning<V> =
		statement {
			isDelete = true
			cb()
		}