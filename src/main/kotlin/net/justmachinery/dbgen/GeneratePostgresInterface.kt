package net.justmachinery.dbgen

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import org.postgresql.jdbc.PgConnection
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Paths
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.*


val defaultOutputDirectory = "build/generated-sources/kotlin"
val defaultEnumPackage = "net.justmachinery.dbgen.enums"
val defaultBeanPackage = "net.justmachinery.dbgen.tables"

class Settings(parser : ArgParser){
	val databaseUrl by parser.storing("URL of database to connect to (including user/pass)")
	val outputDirectory by parser.storing("Directory to output generated source files to").default(defaultOutputDirectory)
	val enumPackage by parser.storing("Package to output enum classes to").default(defaultEnumPackage)
	val beanPackage by parser.storing("Package to output beans to").default(defaultBeanPackage)
	fun enumDirectory() : String = Paths.get(outputDirectory, enumPackage.replace(".", "/")).toString()
	fun beanDirectory() : String = Paths.get(outputDirectory, beanPackage.replace(".", "/")).toString()
}

fun main(args : Array<String>){
	val settings = Settings(ArgParser(args))
	run(settings)
}

fun run(settings : Settings){
	File(settings.enumDirectory()).deleteRecursively()
	File(settings.beanDirectory()).deleteRecursively()

	val connection = DriverManager.getConnection(settings.databaseUrl, Properties()) as PgConnection

	//Generate enum types from Postgres enums
	val userEnumTypes = generateEnumTypes(settings, connection)

	//Generate bean types from tables
	generateTableTypes(settings, connection, userEnumTypes)
}

fun generateEnumTypes(settings : Settings, connection : PgConnection) : List<String> {
	val userEnumTypes = mutableListOf<String>()
	val userTypesResultSet = connection.prepareStatement("SELECT oid, typname FROM pg_type "+"WHERE typcategory = 'E'").executeQuery()
	while(userTypesResultSet.next()){
		val rawName = userTypesResultSet.getString("typname")
		val name = underscoreToCamelCaseTypeName(rawName)
		val id = userTypesResultSet.getInt("oid")
		val values = mutableListOf<String>()
		val valuesRs = connection.prepareStatement("SELECT * FROM pg_enum WHERE enumtypid = $id order by enumsortorder asc").executeQuery()
		while(valuesRs.next()){
			values.add(valuesRs.getString("enumlabel"))
		}
		val output = File("${settings.enumDirectory()}/$name.kt")
		output.parentFile.mkdirs()
		output.createNewFile()
		output.writeText("package ${settings.enumPackage};\nenum class $name { ${values.joinToString(", ")} }")
		userEnumTypes.add(rawName)
	}
	return userEnumTypes
}

fun generateTableTypes(settings : Settings, connection : PgConnection, userEnumTypes : List<String>){
	val tablesResultSet = connection.metaData.getTables(null, null, "", arrayOf("TABLE"))
	val types = mutableListOf<GeneratedType>()
	while(tablesResultSet.next()){
		val rawTableName = tablesResultSet.getString("table_name")
		val typeName = underscoreToCamelCaseTypeName(rawTableName) + "Row"
		val properties = mutableListOf<GeneratedProperty>()

		val columnsResultSet = connection.metaData.getColumns(null, null, rawTableName, null)
		while(columnsResultSet.next()){
			val rawName = columnsResultSet.getString("column_name")
			val postgresTypeName = columnsResultSet.getString("type_name")
			val nullable = columnsResultSet.getString("is_nullable") != "NO"
			properties.add(GeneratedProperty(
					rawName = rawName,
					memberName = underscoreToCamelCaseMemberName(rawName),
					postgresType = postgresTypeName,
					nullable = nullable,
					defaultable = nullable || columnsResultSet.getString("column_def") != null
			))
		}
		types.add(GeneratedType(rawName = rawTableName, typeName = typeName, generatedProperties = properties))
	}
	val renderer = Renderer(settings, userEnumTypes)
	for(type in types){
		renderer.renderType(type)
	}
}

data class GeneratedType(val rawName : String, val typeName : String, val generatedProperties: List<GeneratedProperty>)
data class GeneratedProperty(val rawName : String, val memberName : String, val postgresType : String, val nullable : Boolean, val defaultable : Boolean)

fun String.onlyWhen(condition : Boolean) : String {
	return if(condition){ this } else { "" }
}

class Renderer(val settings : Settings, val userEnumTypes : List<String>) {
	private val GeneratedProperty.kotlinType
		get() = mapPostgresType(this.postgresType)

	fun renderType(type: GeneratedType) {
		val kotlinPath = "${settings.beanDirectory()}/${type.typeName}.kt"
		File(kotlinPath).let {
			it.parentFile.mkdirs()
			it.createNewFile()
		}
		val writer = OutputStreamWriter(FileOutputStream(kotlinPath))
		writer.append("package ${settings.beanPackage}\n")
		renderImports(writer)
		writer.append("data class ${type.typeName}(")
		writer.append(type.generatedProperties.map({
			"val ${it.memberName} : ${it.kotlinType}${"?".onlyWhen(it.nullable)}"
		}).joinToString(", "))
		writer.append(")\n")
		writer.append("{\n")
		renderCompanionMethods(type, writer)
		writer.append("}\n")
		writer.close()
	}

	fun renderImports(writer: OutputStreamWriter) {
		writer.append("import io.jscry.support.InsertOperation\n")
		writer.append("import io.jscry.support.SelectOperation\n")
		writer.append("import io.jscry.support.DeleteOperation\n")
		writer.append("import io.jscry.support.UpdateOperation\n")
		writer.append("import javax.annotation.CheckReturnValue\n")
		writer.append("import java.sql.ResultSet\n")
	}

	fun renderCompanionMethods(type: GeneratedType, writer: OutputStreamWriter) {
		writer.append("\tcompanion object { \n")
		renderInsert(type, writer)
		renderSelect(type, writer)
		renderDelete(type, writer)
		renderUpdate(type, writer)
		writer.append("\t }\n")
	}

	fun propertiesList(type: GeneratedType, transform: (GeneratedProperty) -> String): String {
		return type.generatedProperties.map(transform).joinToString(", ")
	}

	fun renderParameters(
			type: GeneratedType,
			optional: (GeneratedProperty) -> Boolean,
			nameGenerator: (GeneratedProperty) -> String = { it.memberName },
			defaultable: Boolean = true
	): String {
		return propertiesList(type) {
			"${nameGenerator(it)} : ${it.kotlinType}${"?${" = null".onlyWhen(defaultable)}".onlyWhen(optional(it))}"
		}
	}

	fun renderFunction(checkReturnValue: Boolean = true,
	                   name: String,
	                   parameters: String,
	                   returnType: String,
	                   body: String): String {
		return "\t\t${"@CheckReturnValue".onlyWhen(checkReturnValue)} fun $name($parameters) : $returnType {\n\t\t\t$body\n\t\t}\n"
	}

	fun renderInsert(type: GeneratedType, writer: OutputStreamWriter) {
		val columnNames = propertiesList(type) { it.rawName }
		val columnValues = propertiesList(type) {
			if (it.defaultable) "\${if(${it.memberName} == null) \"DEFAULT\" else \"${propSqlParam(it)}\"}"
			else propSqlParam(it)
		}
		val innerSql = "INSERT INTO ${type.rawName}($columnNames) VALUES ($columnValues) RETURNING *"
		val mapContents = propertiesList(type) { "Pair(\"${it.memberName}\", ${transformValueAsNecessary(it.memberName, it.postgresType)})" }
		writer.append(renderFunction(
				name = "insert",
				parameters = renderParameters(type, optional = { it.defaultable }),
				returnType = "InsertOperation<${type.typeName}>",
				body = "return InsertOperation(\"$innerSql\", mapOf($mapContents))"
		))
	}

	fun renderSelect(type: GeneratedType, writer: OutputStreamWriter) {
		val parameterConditions = propertiesList(type) {
			"if(${it.memberName} != null) \"${it.rawName} = ${propSqlParam(it)}\" else null"
		}
		val innerSql = "SELECT * FROM ${type.rawName} WHERE \${listOf($parameterConditions).filterNotNull().joinToString(\" AND \")}"
		val mapContents = propertiesList(type) { "Pair(\"${it.memberName}\", ${transformValueAsNecessary(it.memberName, it.postgresType)})" }
		writer.append(renderFunction(
				name = "select",
				parameters = renderParameters(type, optional = { true }),
				returnType = "SelectOperation<${type.typeName}>",
				body = "return SelectOperation(\"$innerSql\", mapOf($mapContents))"
		))
	}

	fun renderDelete(type: GeneratedType, writer: OutputStreamWriter) {
		val parameterConditions = propertiesList(type) {
			"if(${it.memberName} != null) \"${it.rawName} = ${propSqlParam(it)}\" else null"
		}
		val innerSql = "DELETE FROM ${type.rawName} WHERE \${listOf($parameterConditions).filterNotNull().joinToString(\" AND \")} RETURNING *"
		val mapContents = propertiesList(type) { "Pair(\"${it.memberName}\", ${transformValueAsNecessary(it.memberName, it.postgresType)})" }
		writer.append(renderFunction(
				name = "delete",
				parameters = renderParameters(type, optional = { true }),
				returnType = "DeleteOperation<${type.typeName}>",
				body = "return DeleteOperation(\"$innerSql\", mapOf($mapContents))"
		))
	}

	fun renderUpdate(type: GeneratedType, writer: OutputStreamWriter) {
		val whereMember: (GeneratedProperty) -> String = { "where" + it.memberName.capitalize() }
		val updateParameters = renderParameters(type, optional = { true })
		val whereParametersWithDefaults = renderParameters(type, optional = { true }, nameGenerator = whereMember)
		val whereParametersNoDefaults = renderParameters(type,
				optional = { true },
				defaultable = false,
				nameGenerator = whereMember)
		val updateInterfaceName = "Update${type.typeName}"
		val updates = propertiesList(type) { "if(${it.memberName} != null) \"${it.rawName} = ${propSqlParam(it)}\" else null" }
		val conditions = propertiesList(type) {
			"if(${whereMember(it)} != null) \"${it.rawName} = ${propSqlParam(it, name = whereMember(it))}\" else null"
		}
		val updateSql = "UPDATE ${type.rawName} SET \${listOf($updates).filterNotNull().joinToString(\", \")} WHERE \${listOf($conditions).filterNotNull().joinToString(\" AND \")} RETURNING *"
		val mapContents = propertiesList(type) {
			"Pair(\"${it.memberName}\", ${it.memberName}),Pair(\"${whereMember(it)}\", ${transformValueAsNecessary(whereMember(it), it.postgresType)})"
		}
		writer.append("\t\tinterface $updateInterfaceName { fun where($whereParametersWithDefaults) : UpdateOperation<${type.typeName}> }\n")
		writer.append(renderFunction(
				name = "update",
				parameters = updateParameters,
				returnType = updateInterfaceName,
				body = "return object : $updateInterfaceName { override fun where($whereParametersNoDefaults) : UpdateOperation<${type.typeName}>{ return UpdateOperation(\"$updateSql\", mapOf($mapContents)) } }"
		))
	}

	fun propSqlParam(it: GeneratedProperty, name: String = it.memberName): String {
		return castAsNecessary(":" + name, it.postgresType)
	}

	fun transformValueAsNecessary(value : String, type : String) : String {
		if(userEnumTypes.contains(type)){
			return "$value.toString()"
		} else {
			return value
		}
	}

	fun castAsNecessary(value: String, type: String): String {
		if (listOf("inet", "jsonb").contains(type) || userEnumTypes.contains(type)){ return "CAST ($value AS $type)"}
		else { return value }
	}

	fun mapPostgresType(postgresType: String): String {
		val defaultType = when (postgresType) {
			"bigint" -> Long::class
			"int8" -> Long::class
			"bigserial" -> Long::class
			"boolean" -> Boolean::class
			"bool" -> Boolean::class
			"inet" -> String::class
			"integer" -> Int::class
			"int" -> Int::class
			"int4" -> Int::class
			"json" -> String::class
			"jsonb" -> String::class
			"text" -> String::class
			"timestamp" -> Timestamp::class
			"uuid" -> UUID::class
			else -> null
		}
		if (defaultType != null) {
			return defaultType.qualifiedName!!
		}
		if (userEnumTypes.contains(postgresType)) {
			return "${settings.enumPackage}.${underscoreToCamelCaseTypeName(postgresType)}"
		}
		throw IllegalStateException("Unknown postgres type $postgresType")
	}

}
//Format is TypeName
private fun underscoreToCamelCaseTypeName(name : String) : String {
	return name.split("_").map(String::capitalize).joinToString("")
}

//Format is memberName
private fun underscoreToCamelCaseMemberName(name : String) : String {
	return name.split("_").mapIndexed({ index, it -> if(index>0) it.capitalize() else it } ).joinToString("")
}