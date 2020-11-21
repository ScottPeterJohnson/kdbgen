package net.justmachinery.kdbgen.generation

import com.impossibl.postgres.api.data.Range
import com.impossibl.postgres.api.jdbc.PGConnection
import com.impossibl.postgres.api.jdbc.PGType
import com.impossibl.postgres.jdbc.PGDirectConnection
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import net.justmachinery.kdbgen.generation.utility.Json
import java.sql.Connection
import java.sql.JDBCType
import java.sql.SQLType

internal data class ConvertToSqlContext(
    val paramName : String
)
internal typealias ConvertToSql = ConvertToSqlContext.()->String

internal data class ConvertFromSqlContext(
    val resultName : String
)
internal typealias ConvertFromSql = ConvertFromSqlContext.()->String

internal data class Settings(
	val databaseUrl : String,
	val outputDirectory : String
)

internal class TypeRepr(
    val base : ClassName,
    val nullable : Boolean,
    val params : List<TypeRepr>,
    val oid : Int,
    val sqlRepr : ClassName = base,
    val convertToSql : ConvertToSql = { paramName },
    convertFromSql : ConvertFromSql? = null,
    val isEnum : Boolean = false,
    val isBase : Boolean = false
){
    val convertFromSql : ConvertFromSql = convertFromSql ?: { "$resultName as ${asTypeName()}" }

    fun asTypeName() : TypeName = base
        .let {
            if(params.isNotEmpty()) it.parameterizedBy(*params.map { param -> param.asTypeName() }.toTypedArray()) else it
        }
        .let {
            if(nullable){ it.copy(nullable = true) } else { it.copy(nullable = false) }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypeRepr

        if (oid != other.oid) return false

        return true
    }

    override fun hashCode(): Int {
        return oid
    }
}

internal class TypeContext(val settings: Settings) {
    internal val enums = mutableMapOf<Int, EnumType>()
    internal val domains = mutableMapOf<Int, DomainType>()
    internal val composites = mutableMapOf<Int, CompositeType>()

	internal fun mapPostgresType(connection: PGConnection, oid : Int, nullable: Boolean?): TypeRepr {
        if(oid == 0){ return TypeRepr(
            base = Nothing::class.asTypeName(),
            nullable = true,
            params = emptyList(),
            oid = oid
        ) }


        connection.prepareStatement("""
            select n.nspname = ANY(current_schemas(true)) as onpath, 
                t.*, n.nspname
            from pg_catalog.pg_type t join pg_catalog.pg_namespace n on t.typnamespace = n.oid where t.oid = ?
        """.trimIndent()).use { prep ->
            prep.setInt(1, oid)

            prep.executeQuery().use { rs ->
                if(rs.next()){
                    val onPath = rs.getBoolean("onpath")

                    val typeIsDefined = rs.getBoolean("typisdefined")
                    val schema = rs.getString("nspname")

                    val postgresName = run {
                        val typeName = rs.getString("typname")
                        val fullName = if(onPath) typeName else "\"$schema\".\"$typeName\""
                        PostgresName(typeName, fullName)
                    }
                    if(typeIsDefined){
                        val typeType = rs.getString("typtype")
                        val typeArrayElem = rs.getInt("typelem")
                        if(typeArrayElem != 0 && postgresName.unqualified.startsWith("_")){
                            val arrayType = mapPostgresType(connection, typeArrayElem, null)
                            return TypeRepr(
                                base = List::class.asTypeName(),
                                nullable = nullable ?: true,
                                params = listOf(arrayType),
                                oid = oid,
                                sqlRepr = java.sql.Array::class.asClassName(),
                                convertToSql = {
                                    "net.justmachinery.kdbgen.utility.convertToArray(${paramName}, \"${postgresName.unqualified}\", connection){ ${arrayType.convertToSql(ConvertToSqlContext("it")) } }"
                                },
                                convertFromSql = {
                                    "net.justmachinery.kdbgen.utility.convertFromArray(${resultName}){ ${arrayType.convertFromSql(ConvertFromSqlContext("it")) } }"
                                }
                            )
                        }

                        //See https://www.postgresql.org/docs/current/extend-type-system.html
                        when(typeType){
                            "b" -> {
                                //Base type- JDBC should handle this
                                val result = (connection.unwrap(PGDirectConnection::class.java)).getBaseClass(oid)
                                if(result != null){
                                    return TypeRepr(
                                        base = ClassName.bestGuess(kotlinEquivalent(result)),
                                        sqlRepr = ClassName.bestGuess(result),
                                        oid = oid,
                                        nullable = nullable ?: true,
                                        params = emptyList(),
                                        isBase = true
                                    )
                                }
                                throw IllegalStateException("Unmapped base type $postgresName")
                            }
                            "c" -> {
                                //Composite type
                                val composite = composites.getOrPut(oid){
                                    val typeRelId = rs.getInt("typrelid")
                                    connection.constructCompositeType(postgresName, typeRelId)
                                }
                                return TypeRepr(
                                    base = composite.className,
                                    oid = oid,
                                    sqlRepr = composite.className.nestedClass("SqlData"),
                                    convertFromSql = { "${resultName}.fromSQL()" },
                                    convertToSql = { "${paramName}.toSQL()" },
                                    nullable = nullable ?: true,
                                    params = emptyList()
                                )
                            }
                            "d" -> {
                                //Domain type
                                val typeNotNull = rs.getBoolean("typnotnull")
                                val wraps = rs.getInt("typbasetype")
                                val wrappedType = mapPostgresType(connection, wraps, !typeNotNull)
                                val domain = domains.getOrPut(oid){
                                    DomainType(postgresName, wrappedType)
                                }
                                return TypeRepr(
                                    base = domain.className,
                                    nullable = nullable ?: true,
                                    params = emptyList(),
                                    oid = oid,
                                    sqlRepr = wrappedType.sqlRepr,
                                    convertToSql = { wrappedType.convertToSql(ConvertToSqlContext("$paramName.raw")) },
                                    convertFromSql = {
                                        "${domain.className}(${
                                            wrappedType.convertFromSql(
                                                ConvertFromSqlContext(resultName)
                                            )
                                        })"
                                    }
                                )
                            }
                            "e" -> {
                                //Enum type
                                val enum = enums.getOrPut(oid){
                                    connection.constructEnumType(postgresName, oid)
                                }
                                return TypeRepr(
                                    base = enum.className,
                                    nullable = nullable ?: true,
                                    params = emptyList(),
                                    oid = oid,
                                    sqlRepr = String::class.asClassName(),
                                    convertToSql = { "$paramName.toString()" },
                                    convertFromSql = { "${enum.className}.valueOf($resultName as String)" },
                                    isEnum = true
                                )
                            }
                            "p" -> {
                                //Pseudo type
                                throw IllegalStateException("Unknown pseudo-type $postgresName")
                            }
                            "r" -> {
                                //Range type
                                //https://www.postgresql.org/docs/current/catalog-pg-range.html
                                val range = connection.getRangeTypeInfo(oid) ?: throw IllegalStateException("Can't find range type $postgresName")
                                val rangeType = mapPostgresType(connection, range, false)
                                return TypeRepr(
                                    base = Range::class.asTypeName(),
                                    oid = oid,
                                    nullable = nullable ?: true,
                                    params = listOf(rangeType)
                                )
                            }
                            else -> throw IllegalStateException("Unknown typtype $typeType")
                        }
                    } else {
                        throw IllegalStateException("Type is undefined: $oid, $postgresName")
                    }
                } else {
                    throw IllegalStateException("Cannot find oid $oid")
                }
            }
        }
	}

    private fun kotlinEquivalent(javaClass : String) : String {
        return when(javaClass){
            "java.lang.Integer" -> Int::class.qualifiedName
            "java.lang.Long" -> Long::class.qualifiedName
            "java.lang.Float" -> Float::class.qualifiedName
            "java.lang.Double" -> Double::class.qualifiedName
            "java.lang.Boolean" -> Boolean::class.qualifiedName
            "java.lang.String" -> String::class.qualifiedName
            "java.lang.Number" -> Number::class.qualifiedName
            else -> javaClass
        }!!
    }

    private fun PGDirectConnection.getBaseClass(oid : Int) : String? {
        val type = registry.loadType(oid)
        when(type.name){
            "json", "jsonb" -> return Json::class.qualifiedName!!
        }

        val clazz = type.getCodec(type.parameterFormat).decoder.defaultClass ?: return null
        return clazz.canonicalName
    }

    private fun Connection.getRangeTypeInfo(oid: Int) : Int? {
        val prep = prepareStatement("""
            select * from pg_catalog.pg_range r where r.rngtypid = ?
        """.trimIndent())
        prep.setInt(1, oid)
        prep.executeQuery().use { rs ->
            return if (rs.next()) {
                rs.getInt("rngsubtype")
            } else {
                null
            }
        }
    }

    private fun PGConnection.constructCompositeType(postgresName : PostgresName, typRelId : Int) : CompositeType {
        val columns = mutableListOf<CompositeTypeEntry>()
        val valuesRs = prepareStatement("SELECT * FROM pg_attribute WHERE attrelid = $typRelId order by attnum asc")
            .executeQuery()
        while (valuesRs.next()) {
            val typeOid = valuesRs.getInt("atttypid")
            val attNotNull = valuesRs.getBoolean("attnotnull")
            val representation = mapPostgresType(this, typeOid, !attNotNull)
            val name = valuesRs.getString("attname")
            columns.add(CompositeTypeEntry(name, representation))
        }
        return CompositeType(postgresName, columns)
    }

    private fun Connection.constructEnumType(postgresName : PostgresName, oid : Int) : EnumType {
        val values = mutableListOf<String>()
        prepareStatement("SELECT * FROM pg_enum WHERE enumtypid = $oid order by enumsortorder asc").use {
            val valuesRs = it.executeQuery()
            while (valuesRs.next()) {
                values.add(valuesRs.getString("enumlabel"))
            }
        }
        return EnumType(postgresName, values)
    }
}

data class PostgresName(val unqualified : String, val qualified : String){
    override fun toString() = qualified
}
internal data class EnumType(val postgresName : PostgresName, val values: List<String>){
    val className get() = postgresName.toClassName()
}
internal data class CompositeType(
    val postgresName : PostgresName,
    val columns : List<CompositeTypeEntry>
){
    val className get() = postgresName.toClassName()
}

internal data class CompositeTypeEntry(
    val name : String,
    val repr : TypeRepr
)

internal data class DomainType(val postgresName: PostgresName, val wraps : TypeRepr){
    val className get() = postgresName.toClassName()
}


private fun PostgresName.toClassName() = ClassName.bestGuess("net.justmachinery.kdbgen.sql." + underscoreToCamelCaseTypeName(unqualified))
//Format is TypeName
private fun underscoreToCamelCaseTypeName(name: String): String {
	return name.split("_").joinToString("", transform = { it.capitalize() })
}

//Format is memberName
private fun underscoreToCamelCaseMemberName(name: String): String {
	return name.split("_").mapIndexed { index, it -> if (index > 0) it.capitalize() else it }.joinToString("")
}