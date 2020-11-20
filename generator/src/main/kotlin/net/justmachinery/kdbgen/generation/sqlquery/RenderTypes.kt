package net.justmachinery.kdbgen.generation.sqlquery

import com.impossibl.postgres.api.jdbc.PGType
import com.squareup.kotlinpoet.*
import net.justmachinery.kdbgen.generation.CompositeType
import net.justmachinery.kdbgen.generation.ConvertFromSqlContext
import net.justmachinery.kdbgen.generation.DomainType
import net.justmachinery.kdbgen.generation.EnumType
import java.sql.JDBCType
import java.sql.SQLData
import java.sql.SQLInput
import java.sql.SQLOutput

internal fun renderEnumTypes(builder : FileSpec.Builder, types : List<EnumType>){
    for(type in types){
        val enum = TypeSpec.enumBuilder(type.className)
        for(value in type.values){
            enum.addEnumConstant(value)
        }
        builder.addType(enum.build())
    }
}

internal fun renderDomainTypes(builder : FileSpec.Builder, types : List<DomainType>){
    for(type in types){
        val clazz = TypeSpec.classBuilder(type.className)
        clazz.addModifiers(KModifier.DATA)
        clazz.primaryConstructor(FunSpec.constructorBuilder()
            .addPropertyParameter(clazz, "raw", type.wraps.asTypeName())
            .build())

        builder.addType(clazz.build())
    }
}

internal fun renderCompositeTypes(builder : FileSpec.Builder, types : List<CompositeType>){
    for(type in types){
        val clazz = TypeSpec.classBuilder(type.className)
        clazz.addModifiers(KModifier.DATA)
        clazz.primaryConstructor(FunSpec.constructorBuilder()
            .apply {
                type.columns.forEach {
                    addPropertyParameter(clazz, it.name, it.repr.asTypeName(), private = false)
                }
            }
            .build())

        val toSQL = FunSpec.builder("toSQL")
            .returns(type.className.nestedClass("SqlData"))
            .addCode(StringBuilder().apply {
                appendln("val data = SqlData()")
                type.columns.forEach { column ->
                    appendln("data.${column.name} = ${column.name}")
                }
                appendln("return data")
            }.toString())
        clazz.addFunction(toSQL.build())

        clazz.addType(renderSqlBuilder(type))

        builder.addType(clazz.build())
    }
}

internal fun renderSqlBuilder(composite : CompositeType) : TypeSpec {
    val type = TypeSpec.classBuilder("SqlData")
        .primaryConstructor(FunSpec.constructorBuilder().build())
    type.addSuperinterface(SQLData::class)
    type.addProperty(PropertySpec.builder("__typeName", String::class.asTypeName().copy(nullable = true), KModifier.PRIVATE).initializer("null").mutable().build())
    type.addFunction(FunSpec.builder("getSQLTypeName")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addCode("return %S", composite.postgresName.qualified)
        .build())

    composite.columns.forEach {
        type.addProperty(PropertySpec.builder(it.name, it.repr.asTypeName().copy(nullable = true)).initializer("null").mutable().build())
    }
    val read = FunSpec.builder("readSQL")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("stream", SQLInput::class)
        .addParameter("typeName", String::class)
        .addCode(StringBuilder().apply {
            composite.columns.forEach { column ->
                appendln("${column.name} = stream.readObject(${column.repr.sqlRepr}::class.java).let { ${column.repr.convertFromSql(
                    ConvertFromSqlContext("it")
                )} }")
            }
        }.toString(), composite.postgresName.qualified)

    type.addFunction(read.build())

    val write = FunSpec.builder("writeSQL")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("stream", SQLOutput::class)
        .apply {
            composite.columns.forEach { column ->
                addStatement("stream.writeObject(${column.name}.let { ${
                    column.repr.convertFromSql(
                        ConvertFromSqlContext("it")
                    )
                } }, ${PGType::class.java.asClassName()}.valueOf(%L))", column.repr.oid)
            }
        }

    type.addFunction(write.build())

    val fromSql = FunSpec.builder("fromSQL")
        .returns(composite.className)
        .addCode(StringBuilder().apply {
            appendln("return ${composite.className}(")
            composite.columns.forEach {
                appendln("    ${it.name} = ${it.name}${if(it.repr.nullable) "" else "!!"},")
            }
            appendln(")")
        }.toString())
    type.addFunction(fromSql.build())


    return type.build()
}

fun FunSpec.Builder.addPropertyParameter(clazz : TypeSpec.Builder, name : String, type : TypeName, private : Boolean = true) : FunSpec.Builder {
    this.addParameter(name, type)
    clazz.addProperty(
        PropertySpec.builder(
            name,
            type
        ).initializer(name)
            .apply { if(private) { addModifiers(KModifier.PRIVATE) }  }.build()
    )
    return this
}