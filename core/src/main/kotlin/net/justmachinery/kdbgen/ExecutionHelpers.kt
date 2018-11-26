package net.justmachinery.kdbgen

import java.sql.Connection

interface ConnectionProvider {
	/**
	 * This should return a SQL connection. Users of the interface will not handle closing this connection.
	 */
	fun getConnection() : Connection
}
