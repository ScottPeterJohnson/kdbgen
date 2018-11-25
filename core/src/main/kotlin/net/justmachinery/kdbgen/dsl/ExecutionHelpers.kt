package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.Result1
import net.justmachinery.kdbgen.dsl.clauses.ResultTuple
import java.sql.Connection

interface ConnectionProvider {
	/**
	 * This should return a SQL connection. Users of the interface will not handle closing this connection.
	 */
	fun getConnection() : Connection

	fun StatementReturning<*>.execute() {
		getConnection().let { connection ->
			prepareStatement(this, connection).execute()
		}
	}
	fun StatementReturning<*>.executeUpdate() {
		getConnection().let { connection ->
			prepareStatement(this, connection).executeUpdate()
		}
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
	fun <V> StatementReturning<Result1<V>>.value() : V {
		return executeStatementReturning(this, getConnection()).first().first
	}
	fun <V> StatementReturning<Result1<V>>.valueOrNull() : V? {
		return executeStatementReturning(this, getConnection()).firstOrNull()?.first
	}
	fun <V> StatementReturning<Result1<V>>.values() : List<V> {
		return executeStatementReturning(this, getConnection()).map { it.first }
	}
}
