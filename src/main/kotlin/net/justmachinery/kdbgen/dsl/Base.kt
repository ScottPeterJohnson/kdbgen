package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.*
import net.justmachinery.kdbgen.generation.commonTimestampFull
import net.justmachinery.kdbgen.generation.commonUuidFull
import net.justmachinery.kdbgen.utility.selectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.util.*
import kotlin.reflect.KType

interface Table<Columns> {
	//This was given a property name that cannot appear as a SQL column name, since generated classes are expected to have
	//arbitrarily named column properties
	@Suppress("PropertyName")
	val `table name`: String

	fun columns() : Columns
}

class TableColumn<Type>(val name: String,
                        val rawType: String,
                        val postgresEnum: Boolean,
                        type : KType) : SelectSource<Type>
{
	override val selectSource = RawColumnSource<Type>(name, type)
}



fun executeStatement(statement : StatementReturning<*>, connection: Connection) {
	prepareStatement(statement, connection).execute()
}

fun <Result : ResultTuple> executeStatementReturning(statement : StatementReturning<Result>, connection: Connection): List<Result> {
	val prepared = prepareStatement(statement, connection)
	val resultSet = prepared.executeQuery()
	val results = mutableListOf<Result>()
	while (resultSet.next()) {
		val value = selectMapper(statement.resultClass, statement.builder.selectValues, resultSet)
		results.add(value)
	}
	prepared.close()
	return results
}

fun prepareStatement(statement : StatementReturning<*>, connection : Connection) : PreparedStatement {
	val (sql, parameters) = renderStatement(statement, connection)
	val prepared = connection.prepareStatement(sql)
	for ((index, parameter) in parameters.withIndex()) {
		prepared.setObject(index + 1, parameter)
	}
	return prepared
}

internal fun TableColumn<*>.asParameter(): String {
	return if (
			listOf("inet", "jsonb").contains(rawType)
			|| postgresEnum
	) {
		"CAST (? AS $rawType)"
	} else {
		"?"
	}
}

data class Parameter(val postgresType: String, val value: Any?)

private fun convertParameter(param: Parameter, connection: Connection): Any? {
	if (param.value is List<*>) {
		return connection.createArrayOf(param.postgresType.removeSuffix("[]").removePrefix("_"),
				param.value.toTypedArray())
	} else if (param.value is Enum<*>) {
		return param.value.toString()
	} else if(param.value?.javaClass?.canonicalName == commonTimestampFull){
		val millis = param.value.javaClass.getMethod("getMillis").invoke(param.value) as Long
		val nanos = param.value.javaClass.getMethod("getNanos").invoke(param.value)  as Int
		return Timestamp(millis).apply { this.nanos = nanos }
	} else if(param.value?.javaClass?.canonicalName == commonUuidFull){
		val msb = param.value.javaClass.getMethod("getMostSigBits").invoke(param.value) as Long
		val lsb  = param.value.javaClass.getMethod("getLeastSigBits").invoke(param.value) as Long
		return UUID(msb, lsb)
	}
	return param.value
}

private fun <Result : ResultTuple> renderStatement(statement : StatementReturning<Result>, connection: Connection): Pair<String, List<Any?>> {
	val builder = statement.builder
	val selectValues = builder.selectValues
	val insertValues = builder.insertValues
	val updateValues = builder.updateValues
	val tableName = builder.table.`table name`

	var sql: String
	var parameters: List<Parameter> = emptyList()

	val joins = builder.joinTables.joinToString(", "){ it.`table name` }

	when(builder.operation()){
		SqlOperation.SELECT -> {
			sql = "SELECT ${selectsToParameters(selectValues).joinToString(", ")} FROM $tableName"
			if(builder.joinTables.isNotEmpty()){
				sql += ", "
				sql += joins
			}
		}
		SqlOperation.INSERT -> {
			val columns = insertValues.first().joinToString(", ") { it.first.name }
			val valueList = insertValues.map { values -> "(" + values.joinToString(", ") { it.first.asParameter() } + ")" }
			parameters += insertValues.flatMap { values ->
				values.map {
					Parameter(
							postgresType = it.first.rawType,
							value = it.second
					)
				}
			}
			sql = "INSERT INTO $tableName($columns) VALUES ${valueList.joinToString(",")}"
		}
		SqlOperation.UPDATE -> {
			val sets = updateValues.joinToString(", ") { it.first.name + " = " + it.first.asParameter() }
			parameters += updateValues.map {
				Parameter(
						postgresType = it.first.rawType,
						value = it.second
				)
			}
			sql = "UPDATE $tableName SET $sets"
			if(builder.joinTables.isNotEmpty()){
				sql += " FROM $joins"
			}
		}
		SqlOperation.DELETE -> {
			sql = "DELETE FROM $tableName"
			if(builder.joinTables.isNotEmpty()){
				sql += " USING $joins"
			}
		}
	}

	if (builder.whereClauses.isNotEmpty()) {
		sql += " WHERE " + builder.whereClauses.joinToString(" AND ") { it.sql }
		parameters += builder.whereClauses.flatMap { it.parameters }
	}
	if (builder.operation() !== SqlOperation.SELECT && selectValues.isNotEmpty()) {
		sql += " RETURNING " + selectsToParameters(selectValues).joinToString(", ")
	}
	return Pair(sql, parameters.map { convertParameter(it, connection) })
}

fun selectsToParameters(selects : List<SelectSource<*>>) : List<String> {
	fun parameters(select : SelectSourceBase<*>) : List<String> {
		return when(select){
			is RawColumnSource -> listOf(select.name)
			is DataClassSource -> select.constructorParameters.flatMap { parameters(it) }
		}
	}

	return selects.flatMap { parameters(it.selectSource)}.distinct()
}