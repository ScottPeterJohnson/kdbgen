package net.justmachinery.kdbgen

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
interface OnTarget { val name : String }
//Represents a table whose whole-row data class is DataRow
interface Table<DataRow> : OnTarget
//Represents a column in table that maps to Kotlin Type
class TableColumn<Table, Type>(val name : String, val rawType : String, val postgresEnum : Boolean){
	fun asParameter(name : String = this.name) : String {
		if (listOf("inet", "jsonb").contains(rawType) || postgresEnum){ return "CAST (:$name AS $rawType)"}
		else { return ":$name" }
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

/**
 * Markers for DSLs
 */
interface UpdateInit<Table>{
	infix fun <Value> TableColumn<Table, Value>.setTo(value : Value) {
		@Suppress("UNCHECKED_CAST")
		(this@UpdateInit as StatementImpl<*,Table,*>).addTableColumnValue(Pair(this, value) as Pair<TableColumn<Table, Any?>, Any?>)
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
		S.update(init : UpdateInit<T>.()->Unit) : Statement<Update, T, NotProvided> {
	return toBase(this).copy().apply { type = StatementType.UPDATE; setValues = emptyList(); init() } as Statement<Update, T, NotProvided>
}



fun <Op : SqlOp, On : OnTarget, Result> render(statement : Statement<Op, On, Result>) : Pair<String, List<Pair<String, Any?>>> {
	statement as StatementImpl<Op, On, Result>
	var sql : String
	var parameters : List<Pair<String,Any?>> = statement.whereClauses.map { Pair(it.second.first, it.second.second) }
	if(statement.type == StatementType.SELECT){
		sql = "SELECT ${statement.selectColumns?.map { it.name }?.joinToString(", ") ?: "*"}"
		sql += " FROM ${statement.on.name}"
	} else if (statement.type == StatementType.INSERT) {
		val columns = statement.setValues!!.map { it.first.name }.joinToString(", ")
		val values = statement.setValues!!.map { it.first.asParameter() }.joinToString(", ")
		parameters += statement.setValues!!.map { Pair(it.first.name, it.second) }
		sql = "INSERT INTO ${statement.on.name}($columns) VALUES($values)"
	} else if (statement.type == StatementType.UPDATE) {
		if(statement.setValues?.isEmpty() ?: true){ throw IllegalStateException("No update provided for UPDATE statement: $statement") }
		val sets = statement.setValues!!.map { it.first.name + " = " + it.first.asParameter()}.joinToString(", ")
		parameters += statement.setValues!!.map { Pair(it.first.name, it.second) }
		sql = "UPDATE ${statement.on.name} SET $sets"
	} else if (statement.type == StatementType.DELETE) {
		sql = "DELETE FROM ${statement.on.name}"
	} else {
		throw IllegalStateException("Unknown SQL operation")
	}

	if(statement.whereClauses.isNotEmpty()){
		sql += " WHERE " + statement.whereClauses.map { it.first }.joinToString(" AND ")
	}
	if(statement.type != StatementType.SELECT && statement.selectColumns != null){
		if(statement.selectColumns!!.isEmpty()){
			sql += " RETURNING *"
		} else {
			sql += " RETURNING " + statement.selectColumns!!.map { it.name }.joinToString(", ")
		}
	}
	return Pair(sql, parameters.map({ Pair(it.first, convertParameter(it.second)) }))
}

internal fun convertParameter(param : Any?) : Any? {
	if(param is Collection<*>){
		return param.toTypedArray()
	}
	return param
}

internal enum class StatementType {
	SELECT, UPDATE, INSERT, DELETE
}
internal data class StatementImpl<Operation, On, Returning>(
		val on : On,
		var parameterCount : Int = 0,
		var type : StatementType? = null,
		var setValues: ColumnsToValues<On>? = null,
		//If list is empty, return *
		var selectColumns : List<TableColumn<*, *>>? = null,
		var whereClauses : List<Pair<String, Pair<String, Any?>>> = emptyList()
) :
		Statement<Operation, On, Returning>,
		WhereInit<On>,
		InsertInit<On>,
		UpdateInit<On>
{
	fun addWhereClause(init : (paramName: String)->Pair<String,Any?>){
		val param = toParameterName(parameterCount++)
		val (clause, value) = init(param)
		whereClauses += Pair(clause, Pair(param, value))
	}
	fun addTableColumnValue(pair : Pair<TableColumn<On,Any?>, Any?>){
		setValues = setValues!! + pair
	}
}

internal val characters = "abcdefghijklmnopqrstuvwxyz"
/**
 * Generate an alphabetic parameter name from a counter
 */
fun toParameterName(num : Int) : String {
	return "gen_" + num.toString(26).map { characters[Integer.valueOf(it.toString(), 26)] }.joinToString("")
}



internal fun <Operation, On, Returning> toBase(s : Statement<Operation, On, Returning>) : StatementImpl<Operation, On, Returning> {
	return s as StatementImpl<Operation, On,Returning>
}
