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
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType

interface Table<Columns> {
	fun aliased(alias : String) : Table<Columns>
	val tableName: String

	val columns : Columns
	val columnsList : List<TableColumn<*>>
}

class TableColumn<Type>(val table : Table<*>,
						val name: String,
                        val type: PostgresType) : SelectSource<Type>
{
	override val selectSource = RawColumnSource<Type>(this)
}

data class PostgresType(val type : KType, val rawType : String, val requiresCast : Boolean) {
	val qualifiedName = type.toString().removePrefix("class ")
	fun asParameter(): String {
		return if (
				listOf("inet", "jsonb").contains(rawType)
				|| requiresCast
		) {
			"CAST (? AS $rawType)"
		} else {
			"?"
		}
	}

	fun toArray() : PostgresType {
		return PostgresType(List::class.createType(listOf(KTypeProjection.invariant(type))), rawType.plus("[]"), requiresCast)
	}

}


fun executeStatement(statement : StatementReturning<*>, connection: Connection) {
	prepareStatement(statement, connection).execute()
}

fun <Result : ResultTuple> executeStatementReturning(statement : StatementReturning<Result>, connection: Connection): List<Result> {
	val (prepared , scope) = prepareStatementAndScope(statement, connection)
	val resultSet = prepared.executeQuery()
	val results = mutableListOf<Result>()
	while (resultSet.next()) {
		val value = selectMapper(statement.resultClass, scope, statement.builder.selectValues, resultSet)
		results.add(value)
	}
	prepared.close()
	return results
}

fun prepareStatement(statement : StatementReturning<*>, connection : Connection) : PreparedStatement =
		prepareStatementAndScope(statement, connection).first
fun prepareStatementAndScope(statement : StatementReturning<*>, connection : Connection) : Pair<PreparedStatement, SqlScope> {
	val (sql, parameters, scope) = renderStatement(statement, connection)
	val prepared = connection.prepareStatement(sql)
	for ((index, parameter) in parameters.withIndex()) {
		prepared.setObject(index + 1, parameter)
	}
	return Pair(prepared, scope)
}

private fun <T : Any?> convertParameter(param: SqlClauseValue.Value<T>, connection: Connection): Any? {
	when {
		param.value is List<*> -> return connection.createArrayOf(param.type.rawType.removeSuffix("[]").removePrefix("_"),
				param.value.toTypedArray())
		param.value is Enum<*> -> return param.value.toString()
		param.value is Any -> if (param.type.qualifiedName == commonTimestampFull) {
			val millis = param.value.javaClass.getMethod("getMillis").invoke(param.value) as Long
			val nanos = param.value.javaClass.getMethod("getNanos").invoke(param.value) as Int
			return Timestamp(millis).apply { this.nanos = nanos }
		} else if (param.type.qualifiedName == commonUuidFull) {
			val msb = param.value.javaClass.getMethod("getMostSigBits").invoke(param.value) as Long
			val lsb = param.value.javaClass.getMethod("getLeastSigBits").invoke(param.value) as Long
			return UUID(msb, lsb)
		}
	}
	return param.value
}

class SqlScope {
	private val columnsInScope = mutableMapOf<String, MutableSet<TableColumn<*>>>()
	fun addTable(table : Table<*>){
		table.columnsList.forEach {
			columnsInScope.getOrPut(it.name) { mutableSetOf() }.add(it)
		}
	}
	fun removeTable(table : Table<*>){
		table.columnsList.forEach {
			columnsInScope.getOrPut(it.name) { mutableSetOf() }.remove(it)
		}
	}

	private fun TableColumn<*>.needsQualification() : Boolean {
		val inScope = columnsInScope[name]
		return inScope?.size ?: 0 != 1 || inScope?.contains(this)?.not() ?: false
	}
	private fun TableColumn<*>.render(separator : String) : String {
		if(needsQualification()){
			return table.tableName + separator + name
		} else {
			return name
		}
	}

	fun TableColumn<*>.renderParameter() = render("__")
	fun TableColumn<*>.renderQualified() = render(".")


	fun renderAsSelectParameter(selects : List<SelectSource<*>>) : List<String> {
		fun parameters(select : SelectSourceBase<*>) : List<String> {
			return when(select){
				is RawColumnSource -> {
					val column = select.column
					listOf(if(column.needsQualification()) column.renderQualified() + " AS " + column.renderParameter() else column.name)
				}
				is DataClassSource -> select.constructorParameters.flatMap { parameters(it) }
			}
		}

		return selects.flatMap { parameters(it.selectSource)}.distinct()
	}
}


private data class RenderedStatement(val sql : String, val parameters : List<Any?>, val scope : SqlScope)
private fun <Result : ResultTuple> renderStatement(statement : StatementReturning<Result>, connection: Connection): RenderedStatement {
	val builder = statement.builder
	val selectValues = builder.selectValues
	val insertValues = builder.insertValues
	val updateValues = builder.updateValues
	val tableName = builder.table.tableName

	val scope = SqlScope()
	scope.addTable(builder.table)
	for(join in builder.joinTables){
		scope.addTable(join)
	}

	var sql: String
	var parameters: List<SqlClauseValue.Value<*>> = emptyList()

	val joins = builder.joinTables.joinToString(", "){ it.tableName }

	when(builder.operation()){
		SqlOperation.SELECT -> {
			sql = "SELECT ${scope.renderAsSelectParameter(selectValues).joinToString(", ")} FROM $tableName"
			if(builder.joinTables.isNotEmpty()){
				sql += ", "
				sql += joins
			}
		}
		SqlOperation.INSERT -> {
			val columns = insertValues.flatMap { values -> values.map { it.column } }.distinct()
			val columnNames = columns.joinToString(", ") { it.name }
			val valueList = insertValues.map { values ->
				val byColumn = values.associateBy { it.column }
				val ordered = columns.map { byColumn[it] }
				"(" + ordered.joinToString(", ") { it?.column?.type?.asParameter() ?: "DEFAULT" } + ")"
			}
			parameters += insertValues.flatMap { values ->
				values.map {
					SqlClauseValue.Value(
							type = it.column.type,
							value = it.value
					)
				}
			}
			sql = "INSERT INTO $tableName($columnNames) VALUES ${valueList.joinToString(",")}"

			val conflictClause = builder.conflictClause
			if(conflictClause != null){
				sql += " ON CONFLICT"
				if(conflictClause.columns.isNotEmpty()){
					sql += "(${conflictClause.columns.joinToString(",") { it.name } })"
				}
				if(conflictClause.updates.isNotEmpty()){
					sql += " DO UPDATE SET ${conflictClause.updates.joinToString(",") { it.render(scope) } }"
					parameters += conflictClause.updates.flatMap { it.right.parameters() }
				} else {
					sql += " DO NOTHING"
				}

			}
		}
		SqlOperation.UPDATE -> {
			val sets = updateValues.joinToString(",") { it.render(scope) }
			parameters += updateValues.flatMap { it.right.parameters() }
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
		sql += " WHERE " + builder.whereClauses.joinToString(" AND ") { it.render(scope) }
		parameters += builder.whereClauses.flatMap { it.left.parameters().plus(it.right.parameters()) }
	}
	if (builder.operation() !== SqlOperation.SELECT && selectValues.isNotEmpty()) {
		sql += " RETURNING " + scope.renderAsSelectParameter(selectValues).joinToString(", ")
	}
	return RenderedStatement(sql, parameters.map { convertParameter(it, connection) }, scope)
}
