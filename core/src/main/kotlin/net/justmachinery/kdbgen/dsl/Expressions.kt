package net.justmachinery.kdbgen.dsl

import net.justmachinery.kdbgen.dsl.clauses.Result1
import uy.klutter.reflect.reifiedKType
import kotlin.reflect.KType

data class RenderedSqlFragment(val sql : String, val parameters : List<SqlParameter<*>>){
    companion object {
        fun build(scope : SqlScope, cb : RenderedSqlFragmentBuilder.()->Unit) : RenderedSqlFragment {
            val built = RenderedSqlFragmentBuilder(scope)
            cb(built)
            return built.build()
        }
    }
    class RenderedSqlFragmentBuilder(private val scope : SqlScope) {
        private val sqlParts = mutableListOf<String>()
        private val parameters = mutableListOf<SqlParameter<*>>()
        fun build() = RenderedSqlFragment(sqlParts.joinToString(""), parameters)

        fun add(sql : String){ sqlParts.add(sql) }
        fun add(expr : Expression<*>){
            val rendered = expr.render(scope)
            sqlParts.add(rendered.sql)
            parameters.addAll(rendered.parameters)
        }
        fun add(fragment : RenderedSqlFragment){
            sqlParts.add(fragment.sql)
            parameters.addAll(fragment.parameters)
        }
        fun addJoined(join : String, exprs : Iterable<RenderedSqlFragment>){
            sqlParts.add(exprs.joinToString(join){ it.sql })
            parameters.addAll(exprs.flatMap { it.parameters })
        }
        fun addJoinedExprs(join : String, exprs : Iterable<Expression<*>>){
            addJoined(join, exprs.map { build(scope) { add(it) } })
        }
    }
}

interface Expression<T> {
    fun render(scope : SqlScope) : RenderedSqlFragment

    companion object {
        inline fun <reified T> parameter(value : T, postgresType : PostgresType? = null) : Expression<T> {
            @Suppress("DEPRECATION")
            return SqlParameter(value, reifiedKType<T>(), postgresType)
        }

        fun <T> callFunction(name : String, vararg values : Expression<*>) : Expression<T> {
            return FunctionCallExpression(name, values.toList())
        }
        fun <T> callOperator(name : String, vararg values : Expression<*>) : Expression<T> {
            return OperatorExpression(name, values.toList())
        }
    }
}

internal data class OperatorExpression<T>(val name : String, val values : List<Expression<*>>) : Expression<T> {
    override fun render(scope: SqlScope): RenderedSqlFragment {
        return RenderedSqlFragment.build(scope){
            add("(")
            addJoinedExprs(name, values)
            add(")")
        }
    }
}

internal data class FunctionCallExpression<T>(val name : String, val values : List<Expression<*>>) : Expression<T> {
    override fun render(scope: SqlScope): RenderedSqlFragment {
        return RenderedSqlFragment.build(scope){
            add("$name(")
            addJoinedExprs(", ", values)
            add(")")
        }
    }
}

data class SqlParameter<T>(val value : T, val type : KType, val postgresType : PostgresType? = null) : Expression<T> {
    init {
        if(value is List<*> && postgresType == null){
            throw IllegalStateException(
                "A parameter of type $type is list-like, but a Postgres type was not provided. " +
                "An explicit Postgres type is required due to the way JDBC handles creating arrays."
            )
        }
    }
    override fun render(scope: SqlScope) = RenderedSqlFragment(asParameter(), listOf(this))

    private fun asParameter(): String {
        return if (
            postgresType != null &&
            (listOf("inet", "jsonb").contains(postgresType.rawType)
                || postgresType.requiresCast)
        ) {
            "CAST (? AS ${postgresType.rawType})"
        } else {
            "?"
        }
    }
}

fun <V> subquery(query : StatementReturning<Result1<V>>) : Expression<V> {
    return SubqueryExpression(query)
}

data class SubqueryExpression<T>(val query : StatementReturning<Result1<T>>) : Expression<T> {
    override fun render(scope: SqlScope): RenderedSqlFragment {
        return RenderedSqlFragment.build(scope) {
            add("(")
            add(statementToFragment(query, scope, topmost = false))
            add(")")
        }
    }
}