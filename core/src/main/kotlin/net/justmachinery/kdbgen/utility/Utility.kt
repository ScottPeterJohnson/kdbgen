package net.justmachinery.kdbgen.utility

import java.sql.Connection


inline fun <T> convertFromArray(
	value : Any?,
	subConvert : (Any?)->T
): List<T> {
	val array = (value as java.sql.Array).array as Array<*>
	return array.map {
		subConvert(it)
	}
}
inline fun <T> convertToArray(
	value : Collection<T>,
	postgresType : String,
	connection: Connection,
	subConvert : (T)->Any?
): java.sql.Array {
	val subtype = postgresType.removeSuffix("[]").removePrefix("_")
	return connection.createArrayOf(
		subtype,
		value.mapToArray {
			subConvert(it)
		}
	)
}

inline fun <T, reified R> Collection<T>.mapToArray(cb : (T)->R) : Array<R> {
	val iterator = this.iterator()
	return Array(this.size){
		cb(iterator.next())
	}
}