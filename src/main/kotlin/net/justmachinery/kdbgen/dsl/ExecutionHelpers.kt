package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.Result1
import net.justmachinery.kdbgen.dsl.clauses.ResultTuple
import java.sql.Connection

abstract class ConnectionProvider {
	abstract fun getConnection() : Connection

	fun StatementReturning<*>.execute() {
		executeStatement(this, getConnection())
	}
	inline fun <reified V : ResultTuple> StatementReturning<V>.list() : List<V> {
		return executeStatementReturning(this, getConnection())
	}
	inline fun <reified V : ResultTuple> StatementReturning<V>.single() : V {
		return executeStatementReturning(this, getConnection()).first()
	}
	inline fun <reified V : Any?> StatementReturning<Result1<V>>.value() : V {
		return executeStatementReturning(this, getConnection()).first().first
	}
	inline fun <reified V : Any?> StatementReturning<Result1<V>>.values() : List<V> {
		return executeStatementReturning(this, getConnection()).map { it.first }
	}
}