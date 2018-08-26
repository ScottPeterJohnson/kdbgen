package net.justmachinery.kdbgen

import java.sql.Connection

/**
 * Representation of a statement
 */
interface Statement<Operation, On, Returning>

//Operations
interface SqlOp
interface Select : SqlOp
interface Update : SqlOp
interface Insert : SqlOp
interface Delete : SqlOp

//Marker interface for anything which counts as tables for SQL to act on
interface OnTarget { val _name: String }
//Represents a table whose whole-row data class is DataRow
interface Table<DataRow> : OnTarget
//Represents a column in table that maps to Kotlin Type
class TableColumn<Table, Type>(val name : String, val rawType : String, val postgresEnum : Boolean){
	fun asParameter() : String {
		if (listOf("inet", "jsonb").contains(rawType) || postgresEnum){ return "CAST (? AS $rawType)"}
		else { return "?" }
	}
}


/**
 * Marker types for value insertion DSLs
 */
interface Provided //Required value has been provided
interface NotProvided //Value has not been provided

typealias ColumnsToValues<On> = List<Pair<TableColumn<On, Any?>, Any?>>
//Simple holder class for nullable values (so that the holder itself can be null to represent non-provided values)
data class NullHolder<out T>(val value: T)

data class Parameter(val postgresType : String, val value : Any?)
/**
 * Markers for DSLs
 */
interface UpdateInit<Table>{
	infix fun <Value> TableColumn<Table, Value>.setTo(value : Value) {
		@Suppress("UNCHECKED_CAST")
		(this@UpdateInit as StatementImpl<*,Table,*>).addUpdateValue(Pair(this, value) as Pair<TableColumn<Table, Any?>, Any?>)
	}
}

fun <T : Table<*>> from(table : T) : Statement<NotProvided, T, NotProvided> = StatementImpl(on = table)
//Alias of from for more natural inserts
fun <T : Table<*>> into(table : T) : Statement<NotProvided, T, NotProvided> = StatementImpl(on = table)

@Suppress("UNCHECKED_CAST")
fun <S : Statement<NotProvided, T, NotProvided>, T : Table<*>>
		S.delete() : Statement<Delete, T, NotProvided> {
	return toBase(this).copy().apply { type = StatementType.DELETE } as Statement<Delete, T, NotProvided>
}

@Suppress("UNCHECKED_CAST")
fun <S : Statement<NotProvided, T, NotProvided>, T : Table<*>>
		S.update(init : UpdateInit<T>.(table : T)->Unit) : Statement<Update, T, NotProvided> {
	return toBase(this).copy().apply { type = StatementType.UPDATE; init(this.on) } as Statement<Update, T, NotProvided>
}



fun <Op : SqlOp, On : OnTarget, Result> render(statement : Statement<Op, On, Result>, connection : Connection) : Pair<String, List<Any?>> {
	statement as StatementImpl<Op, On, Result>
	var sql : String
	var parameters : List<Parameter> = emptyList()
	if(statement.type == StatementType.SELECT){
		sql = "SELECT ${statement.selectColumns?.map { it.name }?.joinToString(", ") ?: "*"}"
		sql += " FROM ${statement.on._name}"
	} else if (statement.type == StatementType.INSERT) {
		val columns = statement.insertValues.first().map { it.first.name }.joinToString(", ")
		val values = statement.insertValues.map { "(" + it.map { it.first.asParameter() }.joinToString(", ") + ")" }
		parameters += statement.insertValues.flatMap { it.map { Parameter(
				postgresType = it.first.rawType,
				value = it.second
		) } }
		sql = "INSERT INTO ${statement.on._name}($columns) VALUES ${values.joinToString(",")}"
	} else if (statement.type == StatementType.UPDATE) {
		if(statement.updateValues.isEmpty()){ throw IllegalStateException("No update provided for UPDATE statement: $statement") }
		val sets = statement.updateValues.map { it.first.name + " = " + it.first.asParameter()}.joinToString(", ")
		parameters += statement.updateValues.map { Parameter(
				postgresType = it.first.rawType,
				value = it.second
		) }
		sql = "UPDATE ${statement.on._name} SET $sets"
	} else if (statement.type == StatementType.DELETE) {
		sql = "DELETE FROM ${statement.on._name}"
	} else {
		throw IllegalStateException("Unknown SQL operation")
	}

	if(statement.whereClauses.isNotEmpty()){
		sql += " WHERE " + statement.whereClauses.map { it.sql }.joinToString(" AND ")
		parameters += statement.whereClauses.map { Parameter(it.postgresType, it.paramValue) }
	}
	if(statement.type != StatementType.SELECT && statement.selectColumns != null){
		if(statement.selectColumns!!.isEmpty()){
			sql += " RETURNING *"
		} else {
			sql += " RETURNING " + statement.selectColumns!!.map { it.name }.joinToString(", ")
		}
	}
	return Pair(sql, parameters.map({ convertParameter(it, connection) }))
}

fun <Op : SqlOp, On : OnTarget> Statement<Op,On,NotProvided>.execute(connection : Connection) {
	val (sql, parameters) = render(this, connection)
	val statement = connection.prepareStatement(sql)
	for((index, parameter) in parameters.withIndex()){
		statement.setObject(index+1, parameter)
	}
	statement.execute()
}

inline fun <Op : SqlOp, On : OnTarget, reified Result : Any> Statement<Op,On,Result>.execute(connection : Connection) : List<Result> {
	val (sql, parameters) = render(this, connection)
	val statement = connection.prepareStatement(sql)
	for((index, parameter) in parameters.withIndex()){
		statement.setObject(index+1, parameter)
	}
	val resultMapper = resultMapper(Result::class)
	val resultSet = statement.executeQuery()
	val results = mutableListOf<Result>()
	while(resultSet.next()){
		results.add(resultMapper.invoke(resultSet))
	}
	statement.close()
	return results
}

internal fun convertParameter(param : Parameter, connection : Connection) : Any? {
	if(param.value is List<*>){
		return connection.createArrayOf(param.postgresType.removeSuffix("[]").removePrefix("_"), param.value.toTypedArray())
	} else if (param.value is Enum<*>){
		return param.value.toString()
	}
	return param.value
}

internal enum class StatementType {
	SELECT, UPDATE, INSERT, DELETE
}
internal data class StatementImpl<Operation, On, Returning>(
		val on : On,
		var parameterCount : Int = 0,
		var type : StatementType? = null,
		val updateValues: MutableList<Pair<TableColumn<On, Any?>, Any?>> = mutableListOf(),
		val insertValues : MutableList<ColumnsToValues<On>> = mutableListOf(),
		//If list is empty, return *
		var selectColumns : List<TableColumn<*, *>>? = null,
		var whereClauses : List<WhereClause> = emptyList()
) :
		Statement<Operation, On, Returning>,
		WhereInit<On>,
		InsertInit<On>,
		UpdateInit<On>
{
	fun addWhereClause(sql : String, paramType : String, paramValue : Any?){
		whereClauses += WhereClause(sql, paramType, paramValue)
	}
	fun addUpdateValue(value : Pair<TableColumn<On, Any?>, Any?>){
		updateValues.add(value)
	}
	fun addInsertValues(values : ColumnsToValues<On>){
		insertValues.add(values)
	}
}

internal data class WhereClause(val sql : String, val postgresType : String, val paramValue : Any?)

internal fun <Operation, On, Returning> toBase(s : Statement<Operation, On, Returning>) : StatementImpl<Operation, On, Returning> {
	return s as StatementImpl<Operation, On,Returning>
}
