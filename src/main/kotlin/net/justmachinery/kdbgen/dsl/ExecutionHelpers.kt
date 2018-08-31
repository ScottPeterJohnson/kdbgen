package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.Result1
import net.justmachinery.kdbgen.dsl.clauses.ResultTuple
import java.sql.Connection

interface ConnectionProvider {
	fun getConnection() : Connection

	fun StatementReturning<*>.execute() {
		executeStatement(this, getConnection())
	}
	fun <V : ResultTuple> StatementReturning<V>.list() : List<V> {
		return executeStatementReturning(this, getConnection())
	}
	fun <V : ResultTuple> StatementReturning<V>.single() : V {
		return executeStatementReturning(this, getConnection()).first()
	}
	fun <V : ResultTuple> StatementReturning<V>.singleOrNull() : V? {
		return executeStatementReturning(this, getConnection()).firstOrNull()
	}
	fun <V : Any?> StatementReturning<Result1<V>>.value() : V {
		return executeStatementReturning(this, getConnection()).first().first
	}
	fun <V : Any?> StatementReturning<Result1<V>>.valueOrNull() : V? {
		return executeStatementReturning(this, getConnection()).firstOrNull()?.first
	}
	fun <V : Any?> StatementReturning<Result1<V>>.values() : List<V> {
		return executeStatementReturning(this, getConnection()).map { it.first }
	}
}