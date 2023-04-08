package net.justmachinery.kdbgen

import java.sql.Connection
import java.sql.PreparedStatement

interface ConnectionProvider {
	/**
	 * This should invoke the specified block with a valid SQL connection. Users of the interface will not handle closing this connection.
	 */
	fun <T> useConnection(cb : (Connection)->T) : T
}

abstract class KdbGenQuery<Params, Result, Batch : BatchPreparation<Params, Result>> {
	abstract val connectionProvider : ConnectionProvider
	abstract val sql : String
	protected open val __arrays: List<java.sql.Array>? = null

	fun invoke(params : Params) : List<Result> {
		return connectionProvider.useConnection { connection ->
			val prepared = connection.prepareStatement(sql)
			prepared.use {
				setParameters(connection, prepared, params)
				try {
					prepared.execute()
				} finally {
					__arrays?.let {
						it.forEach { it.free() }
					}
				}
				extract(prepared)
			}
		}
	}
	protected fun execute(params : Params) {
		connectionProvider.useConnection { connection ->
			val prepared = connection.prepareStatement(sql)
			prepared.use {
				setParameters(connection, prepared, params)
				try {
					prepared.execute()
				} finally {
					__arrays?.let {
						it.forEach { it.free() }
					}
				}
			}
		}
	}
	fun batch(cb: Batch.()->Unit) : IntArray {
		return connectionProvider.useConnection { connection ->
			val prepared = connection.prepareStatement(sql)
			prepared.use {
				val batch = instantiateBatch(connection, prepared)
				cb(batch)
				try {
					prepared.executeBatch()
				} finally {
					__arrays?.let {
						it.forEach { it.free() }
					}
				}
			}
		}
	}

	protected abstract fun extract(prepared: PreparedStatement) : List<Result>
	abstract fun setParameters(connection: Connection, prepared : PreparedStatement, params : Params)
	protected abstract fun instantiateBatch(connection: Connection, prepared: PreparedStatement) : Batch
}


interface BatchPreparation<Params, Result> {
	var count : Int
	val query : KdbGenQuery<Params, Result, *>
	val connection : Connection
	val prepared : PreparedStatement

	fun add(params : Params){
		count += 1
		query.setParameters(connection, prepared, params)
		prepared.addBatch()
	}
}
