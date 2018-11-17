package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.Result1
import net.justmachinery.kdbgen.dsl.clauses.ResultTuple
import java.sql.Connection

interface ConnectionProvider {
	fun getConnection() : Connection

	fun StatementReturning<*>.execute() {
		getConnection().use { connection ->
			prepareStatement(this, connection).execute()
		}
	}
	fun StatementReturning<*>.executeUpdate() {
		getConnection().use { connection ->
			prepareStatement(this, connection).executeUpdate()
		}
	}
	fun <V : ResultTuple> StatementReturning<V>.list() : List<V> {
		return internalExecuteStatementReturning(this, getConnection())
	}
	fun <V : ResultTuple> StatementReturning<V>.single() : V {
		return internalExecuteStatementReturning(this, getConnection()).first()
	}
	fun <V : ResultTuple> StatementReturning<V>.singleOrNull() : V? {
		return internalExecuteStatementReturning(this, getConnection()).firstOrNull()
	}
	fun <V> StatementReturning<Result1<V>>.value() : V {
		return internalExecuteStatementReturning(this, getConnection()).first().first
	}
	fun <V> StatementReturning<Result1<V>>.valueOrNull() : V? {
		return internalExecuteStatementReturning(this, getConnection()).firstOrNull()?.first
	}
	fun <V> StatementReturning<Result1<V>>.values() : List<V> {
		return internalExecuteStatementReturning(this, getConnection()).map { it.first }
	}
	private fun <Result : ResultTuple> internalExecuteStatementReturning(statement : StatementReturning<Result>, connection: Connection): List<Result> {
		getConnection().use {
			return executeStatementReturning(statement, connection)
		}
	}
}
