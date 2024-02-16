package net.justmachinery.kdbgen.generation.sqlquery

import com.impossibl.postgres.api.jdbc.PGType
import com.squareup.kotlinpoet.*
import net.justmachinery.kdbgen.generation.CompositeType
import net.justmachinery.kdbgen.generation.ConvertFromSqlContext
import net.justmachinery.kdbgen.generation.DomainType
import net.justmachinery.kdbgen.generation.EnumType
import java.sql.SQLData
import java.sql.SQLInput
import java.sql.SQLOutput

internal fun renderEnumType(builder : FileSpec.Builder, type : EnumType){
    val enum = TypeSpec.enumBuilder(type.className)
    for(value in type.values){
        enum.addEnumConstant(value)
    }
    builder.addType(enum.build())
}

internal fun renderDomainType(builder : FileSpec.Builder, type : DomainType){
    val clazz = TypeSpec.classBuilder(type.className)
    clazz.addModifiers(KModifier.DATA)
    clazz.primaryConstructor(FunSpec.constructorBuilder()
        .addPropertyParameter(clazz, "raw", type.wraps.asTypeName())
        .build())

    builder.addType(clazz.build())
}

internal fun renderCompositeType(builder : FileSpec.Builder, type : CompositeType){
    val clazz = TypeSpec.classBuilder(type.className)
    clazz.addModifiers(KModifier.DATA)
    clazz.primaryConstructor(FunSpec.constructorBuilder()
        .apply {
            type.columns.forEach {
                addPropertyParameter(clazz, it.name, it.repr.asTypeName(), modifier = KModifier.PUBLIC)
            }
        }
        .build())

    val toSQL = FunSpec.builder("toSQL")
        .returns(type.className.nestedClass("SqlData"))
        .addCode(StringBuilder().apply {
            appendLine("val data = SqlData()")
            type.columns.forEach { column ->
                appendLine("data.${column.name} = ${column.name}")
            }
            appendLine("return data")
        }.toString())
    clazz.addFunction(toSQL.build())

    clazz.addType(renderSqlBuilder(type))

    builder.addType(clazz.build())
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

fun FunSpec.Builder.addPropertyParameter(clazz : TypeSpec.Builder, name : String, type : TypeName, modifier : KModifier = KModifier.PRIVATE) : FunSpec.Builder {
    this.addParameter(name, type)
    clazz.addProperty(
        PropertySpec.builder(
            name,
            type
        ).initializer(name)
            .apply { addModifiers(modifier)  }.build()
    )
    return this
}