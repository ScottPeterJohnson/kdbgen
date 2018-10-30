package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.commonTimestampFull
import net.justmachinery.kdbgen.commonUuidFull
import net.justmachinery.kdbgen.dsl.clauses.*
import net.justmachinery.kdbgen.utility.selectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.jvmErasure

interface SqlDslBase

@Suppress("unused")
inline fun <reified T> SqlDslBase.parameter(value : T) = Expression.parameter(value)

interface Table<Columns> {
	fun aliased(alias : String) : Table<Columns>
	val tableName: String

	val columns : Columns
	val columnsList : List<TableColumn<*>>


}

class TableColumn<Type>(val table : Table<*>,
						val name: String,
                        val type: PostgresType) : Selectable<Type>, Expression<Type>
{
    @Suppress("UNCHECKED_CAST")
    override fun construct(values: List<Any?>): Type {
        return values.first() as Type
    }

    override fun toExpressions(): List<TypedExpression<*>> = listOf(TypedExpression(this, type))

	override fun render(scope: SqlScope): RenderedSqlFragment {
		return RenderedSqlFragment(scope.referenceUnambiguously(this), listOf())
	}

	fun insertValue(value : Type) = SqlInsertValue(this, SqlParameter(value, this.type.type, postgresType = this.type))
}

data class PostgresType(val type : KType, val rawType : String, val requiresCast : Boolean) {
	val qualifiedName = type.toString().removePrefix("class ")

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
internal fun prepareStatementAndScope(statement : StatementReturning<*>, connection : Connection) : Pair<PreparedStatement, SqlScope> {
	val (sql, parameters, scope) = renderStatement(statement, connection)
	val prepared = connection.prepareStatement(sql)
	for ((index, parameter) in parameters.withIndex()) {
		prepared.setObject(index + 1, parameter)
	}
	return Pair(prepared, scope)
}

private fun <T> convertToParameterType(parameter : SqlParameter<T>, connection: Connection): Any? {
	val (value, type, postgresType) = parameter
	when (value) {
		is List<*> -> {
			val listType = type.arguments.first().type!!
			val subtype = postgresType!!.rawType.removeSuffix("[]").removePrefix("_")
			return connection.createArrayOf(
				subtype,
				value.map {
					convertToParameterType(SqlParameter(
						it,
						listType,
						postgresType.copy(
							type = listType,
							rawType = subtype
						)
					), connection)
				}.toTypedArray()
			)
		}
		is Enum<*> -> return value.toString()
		is Any -> if (type.jvmErasure.qualifiedName == commonTimestampFull) {
			val millis = value.javaClass.getMethod("getMillis").invoke(value) as Long
			val nanos = value.javaClass.getMethod("getNanos").invoke(value) as Int
			return Timestamp(millis).apply { this.nanos = nanos }
		} else if (type.jvmErasure.qualifiedName == commonUuidFull) {
			val msb = value.javaClass.getMethod("getMostSigBits").invoke(value) as Long
			val lsb = value.javaClass.getMethod("getLeastSigBits").invoke(value) as Long
			return UUID(msb, lsb)
		}
	}
	return value
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

	private fun needsQualification(column : TableColumn<*>) : Boolean {
		val inScope = columnsInScope[column.name]
		return inScope?.size ?: 0 != 1 || inScope?.contains(column)?.not() ?: false
	}

	fun referenceUnambiguously(column : TableColumn<*>) : String {
		return if(needsQualification(column)){
			column.table.tableName + "." + column.name
		} else {
			column.name
		}
	}


    private var selectableColumnResolution = emptyMap<Selectable<*>, List<AliasAndType>>()
    internal data class AliasAndType(val alias : String, val type : KType)
    internal fun resolve(selectable : Selectable<*>) = selectableColumnResolution[selectable]!!

	fun renderSelections(topmost : Boolean, selects : List<Selectable<*>>) : List<RenderedSqlFragment> {
		val selectionInfo = selects
            .associateBy(
                { it },
                { it.toExpressions().map { expr -> Pair(expr.expression.render(this), expr) } }
            )
        val aliases = selectionInfo.values
            .flatten()
            .map { it.first }
            .distinct()
            .withIndex()
            .associateBy({it.value}, {"_${it.index}"})

        if(topmost) selectableColumnResolution = selectionInfo
            .mapValues { (_, values) ->
                values.map {
                    AliasAndType(aliases[it.first]!!, it.second.type.type)
                }
            }
        return aliases.map { RenderedSqlFragment.build(this) {
			add(it.key)
			add(" AS ${it.value}")
		} }
	}
}


private data class RenderedStatement(val sql : String, val parameters : List<Any?>, val scope : SqlScope)
private fun <Result : ResultTuple> renderStatement(statement : StatementReturning<Result>, connection: Connection): RenderedStatement {
	val scope = SqlScope()

	val fragment = statementToFragment(statement, scope, true)

	return RenderedStatement(fragment.sql, fragment.parameters.map { convertToParameterType(it, connection) }, scope)
}

internal fun statementToFragment(statement : StatementReturning<*>, scope : SqlScope, topmost : Boolean) : RenderedSqlFragment {
	val builder = statement.builder
	val selectValues = builder.selectValues
	val insertValues = builder.insertValues
	val updateValues = builder.updateValues
	val tableName = builder.table.tableName

	sanityCheckStatement(statement)

	scope.addTable(builder.table)
	for(join in builder.joinTables){
		scope.addTable(join)
	}


	val joins = builder.joinTables.joinToString(", "){ it.tableName }

	val fragment = RenderedSqlFragment.build(scope) {

		fun addWhereClauses(clauses : List<WhereClause> = builder.whereClauses){
			if (clauses.isNotEmpty()) {
				add(" WHERE ")
				addJoined(" AND ", clauses.map { it.render(scope) })
			}
		}

		fun addReturning(){
			if (builder.operation() !== SqlOperation.SELECT && selectValues.isNotEmpty()) {
				add(" RETURNING ")
				addJoined(", ", scope.renderSelections(topmost, selectValues))
			}
		}

		when(builder.operation()){
			SqlOperation.SELECT -> {
				add("SELECT ")
				addJoined(", ", scope.renderSelections(topmost, selectValues))
				add(" FROM $tableName")
				if(builder.joinTables.isNotEmpty()){
					add(", ")
					add(joins)
				}

				addWhereClauses()

				if(builder.selectForUpdate){
					add(" FOR UPDATE")
				}
				if(builder.selectSkipLocked){
					add(" SKIP LOCKED")
				}
				if(builder.selectLimit != null){
					add(" LIMIT ")
					add(Expression.parameter(builder.selectLimit))
				}
			}
			SqlOperation.INSERT -> {
				add("INSERT INTO $tableName(")
				//Find a list of columns involved in this insert across all value lists
				val columns = insertValues.flatMap { values -> values.map { it.column } }.distinct()
				add(columns.joinToString(", ") { it.name })
				add(") VALUES ")


				//Order every insert-value list to comply with above, and render it
				addJoined(", ", insertValues.map { rowValues ->
					val byColumn = rowValues.associateBy { it.column }
					val orderedRowValues = columns.map { byColumn[it]?.value?.render(scope) ?: RenderedSqlFragment("DEFAULT", listOf()) }
					RenderedSqlFragment.build(scope) {
						add("(")
						addJoined(", ", orderedRowValues)
						add(")")
					}
				})

				val conflictClause = builder.conflictClause
				if(conflictClause != null){
					add(" ON CONFLICT")
					val excluded = builder.table.aliased("excluded")
					scope.addTable(excluded)
					if(conflictClause.columns.isNotEmpty()){
						add("(${conflictClause.columns.joinToString(",") { it.name } })")
					}
					if(conflictClause.updates.isNotEmpty()){
						add(" DO UPDATE SET ")
						addJoined(", ", conflictClause.updates.map { it.render(scope) })
						addWhereClauses(conflictClause.whereClauses)
					} else {
						add(" DO NOTHING")
					}
					scope.removeTable(excluded)

				}

				addReturning()
			}
			SqlOperation.UPDATE -> {
				add("UPDATE $tableName SET ")
				addJoined(", ", updateValues.map { it.render(scope) })

				if(builder.joinTables.isNotEmpty()){
					add(" FROM $joins")
				}

				addWhereClauses()
				addReturning()
			}
			SqlOperation.DELETE -> {
				add("DELETE FROM $tableName")
				if(builder.joinTables.isNotEmpty()){
					add(" USING $joins")
				}

				addWhereClauses()
				addReturning()
			}
		}
	}

	scope.removeTable(builder.table)
	for(join in builder.joinTables){
		scope.removeTable(join)
	}

	return fragment
}

private fun sanityCheckStatement(statement : StatementReturning<*>){
	if(statement.builder.whereClauses.isEmpty()
		&& listOf(SqlOperation.DELETE, SqlOperation.UPDATE).contains(statement.builder.operation())){
		throw RuntimeException("""
    		${statement.builder.operation()} has no WHERE clauses!
    		This is probably a very dangerous bug. If not, add a dummy where clause.
    	""".trimIndent())
	}
}