package net.justmachinery.kdbgen

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

const val kdbGen = "net.justmachinery.kdbgen"

const val defaultOutputDirectory = "build/generated-sources/kotlin"
const val defaultEnumPackage = "net.justmachinery.kdbgen.enums"
const val defaultDataPackage = "net.justmachinery.kdbgen.tables"

class Settings(parser: ArgParser) {
	val databaseUrl by parser.storing("URL of database to connect to (including user/pass)")
	private val outputDirectory by parser.storing("Directory to output generated source files to").default(
			defaultOutputDirectory)
	private val dslOutputDirectory by parser.storing("Directory to output DSL helpers to, if different than output directory").default(
			null)
	val primitiveOnly by parser.flagging("Outputs types only as Kotlin primitives. Timestamps will be represented as Longs (losing precision below the millisecond level), and UUIDs as strings.")
	val enumPackage by parser.storing("Package to output enum classes to").default(defaultEnumPackage)
	val dataPackage by parser.storing("Package to output beans and DSL to").default(defaultDataPackage)

	private fun directory(directory: String, `package`: String) = Paths.get(directory,
			`package`.replace(".", "/")).toString()

	fun enumDirectory(): String = directory(outputDirectory, enumPackage)
	fun dataDirectory(): String = directory(outputDirectory, dataPackage)
	fun dslDirectory(): String = directory(dslOutputDirectory ?: outputDirectory, dataPackage)
}

fun main(args: Array<String>) {
	val settings = Settings(ArgParser(args))
	run(settings)
}

fun run(settings: Settings) {
	File(settings.enumDirectory()).deleteRecursively()
	File(settings.dataDirectory()).deleteRecursively()
	File(settings.dslDirectory()).deleteRecursively()

	val connection = DriverManager.getConnection(settings.databaseUrl, Properties()) as PgConnection

	//Generate enum types from Postgres enums
	val userEnumTypes = generateEnumTypes(settings, connection)

	//Generate bean types from tables
	generateTableTypes(settings, connection, userEnumTypes)
}

fun generateEnumTypes(settings: Settings, connection: PgConnection): List<String> {
	val userEnumTypes = mutableListOf<String>()
	val userTypesResultSet = connection.prepareStatement("SELECT oid, typname FROM pg_type " + "WHERE typcategory = 'E'").executeQuery()
	while (userTypesResultSet.next()) {
		val rawName = userTypesResultSet.getString("typname")
		val name = underscoreToCamelCaseTypeName(rawName)
		val id = userTypesResultSet.getInt("oid")
		val values = mutableListOf<String>()
		val valuesRs = connection.prepareStatement("SELECT * FROM pg_enum WHERE enumtypid = $id order by enumsortorder asc").executeQuery()
		while (valuesRs.next()) {
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

fun generateTableTypes(settings: Settings, connection: PgConnection, userEnumTypes: List<String>) {
	val tablesResultSet = connection.metaData.getTables(null, null, "", arrayOf("TABLE"))
	val types = mutableListOf<GeneratedType>()
	while (tablesResultSet.next()) {
		val rawTableName = tablesResultSet.getString("table_name")
		val properties = mutableListOf<GeneratedProperty>()

		val columnsResultSet = connection.metaData.getColumns(null, null, rawTableName, null)
		while (columnsResultSet.next()) {
			val rawName = columnsResultSet.getString("column_name")
			val postgresTypeName = columnsResultSet.getString("type_name")
			val nullable = columnsResultSet.getString("is_nullable") != "NO"
			properties.add(GeneratedProperty(
					rawName = rawName,
					postgresType = postgresTypeName,
					nullable = nullable,
					defaultable = nullable || columnsResultSet.getString("column_def") != null
			))
		}
		types.add(GeneratedType(rawName = rawTableName, generatedProperties = properties))
	}
	val renderer = Renderer(settings, userEnumTypes)
	for (type in types) {
		renderer.renderType(type)
	}
}

data class GeneratedType(val rawName: String, val generatedProperties: List<GeneratedProperty>) {
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}

data class GeneratedProperty(val rawName: String,
                             val postgresType: String,
                             val nullable: Boolean,
                             val defaultable: Boolean) {
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}

fun String.onlyWhen(condition: Boolean): String {
	return if (condition) {
		this
	} else {
		""
	}
}

class Renderer(private val settings: Settings, private val userEnumTypes: List<String>) {
	private val GeneratedProperty.kotlinType
		get() = mapPostgresType(this.postgresType).plus("?".onlyWhen(this.nullable))

	private fun getOutputStream(directory: String, type: GeneratedType): OutputStreamWriter {
		val fullPath = "$directory/${type.className}.kt"
		File(fullPath).let {
			it.parentFile.mkdirs()
			it.createNewFile()
		}
		return OutputStreamWriter(FileOutputStream(fullPath))
	}

	fun renderType(type: GeneratedType) {
		val dslWriter = getOutputStream(settings.dslDirectory(), type)

		dslWriter.append("@file:Suppress(\"UNCHECKED_CAST\", \"unused\", \"PropertyName\", \"SimplifiableCallChain\")\n")
		dslWriter.append("package ${settings.dataPackage}\n")
		renderImports(dslWriter)

		renderRowClass(settings, type, dslWriter)

		val tableClassName = "${type.className}Table"
		dslWriter.append("class $tableClassName : Table<${type.className}Row> {\n")
		dslWriter.append("\toverride val _name = \"${type.rawName}\"\n")
		renderColumnDefinitions(type, dslWriter)
		dslWriter.append("}\n")
		dslWriter.append("val ${type.memberName}Table = ${type.className}Table()\n")

		//Insert DSL support
		val insertClassName = "${type.className}TableInsert"
		dslWriter.append("data class $insertClassName<${type.generatedProperties.map { it.className }.joinToString(", ")}>(\n")
		dslWriter.append(type.generatedProperties.map {
			"\tval _${it.memberName} : NullHolder<${it.kotlinType}>? = null"
		}.joinToString(",\n"))
		dslWriter.append("\n)\n")
		dslWriter.append("{\n")
		dslWriter.append("internal fun toCols() : List<Pair<TableColumn<$tableClassName, Any?>,Any?>> {\n")
		dslWriter.append("\treturn listOf(\n")
		dslWriter.append(type.generatedProperties.map {
			"\t\t_${it.memberName}?.let { Pair(${type.memberName}Table.${it.memberName} as TableColumn<$tableClassName,Any?>, it.value as Any?) }"
		}.joinToString(",\n"))
		dslWriter.append("\t).filterNotNull()\n")
		dslWriter.append("}\n")
		dslWriter.append("}\n")

		val notProvided = type.generatedProperties.map { if (it.defaultable) "Provided" else "NotProvided" }.joinToString(
				",")
		val provided = type.generatedProperties.map { "Provided" }.joinToString(",")
		dslWriter.append("typealias ${insertClassName}Init = $insertClassName<$notProvided>\n")
		dslWriter.append("fun InsertInit<$tableClassName>.values(vals : ($insertClassName<$notProvided>)->$insertClassName<$provided>) : List<ColumnsToValues<$tableClassName>> { return this.values(listOf(vals)) }\n")
		dslWriter.append("fun InsertInit<$tableClassName>.values(vals : List<($insertClassName<$notProvided>)->$insertClassName<$provided>>) : List<ColumnsToValues<$tableClassName>> {\n")
		dslWriter.append("\treturn vals.map {\n")
		dslWriter.append("\t\tval insert = it($insertClassName())\n")
		dslWriter.append("\t\tinsert.toCols()\n")
		dslWriter.append("\t}")
		dslWriter.append("}\n")


		type.generatedProperties.forEachIndexed { index, it ->
			val parameters = (0..(type.generatedProperties.size - 2)).map { "P$it" }
			val parameterization = { type: String ->
				val list = parameters.toMutableList()
				list.add(index, type)
				list.joinToString(",")
			}
			val inputType = "$insertClassName<${parameterization(if (it.defaultable) "*" else "NotProvided")}>"
			val resultType = "$insertClassName<${parameterization("Provided")}>"
			val paramStr = if (parameters.isNotEmpty()) "<${parameters.joinToString(",")}>" else ""
			dslWriter.append("fun $paramStr $inputType.${it.memberName}(${it.memberName} : ${it.kotlinType}) : $resultType = this.copy(_${it.memberName} = NullHolder(${it.memberName})) as $resultType\n")
		}
		dslWriter.close()
	}

	private fun renderRowClass(settings: Settings, type: GeneratedType, writer: OutputStreamWriter) {
		fun writeTemplate(actualWriter: OutputStreamWriter) {
			actualWriter.append("data class ${type.className}Row(")
			actualWriter.append(type.generatedProperties.map {
				"val ${it.memberName} : ${it.kotlinType}"
			}.joinToString(", "))
			actualWriter.append(")\n")
		}

		if (settings.dslDirectory() != settings.dataDirectory()) {
			val dataclassWriter = getOutputStream(settings.dataDirectory(), type)
			dataclassWriter.append("package ${settings.dataPackage}\n")
			writeTemplate(dataclassWriter)
			dataclassWriter.close()
		} else {
			writeTemplate(writer)
		}
	}

	private fun renderImports(writer: OutputStreamWriter) {
		writer.append("import $kdbGen.*\n")
	}

	private fun renderColumnDefinitions(type: GeneratedType, writer: OutputStreamWriter) {
		type.generatedProperties.forEach {
			writer.append("\tval ${it.memberName} = TableColumn<${type.className}Table, ${it.kotlinType}>(\"${it.rawName}\", \"${it.postgresType}\", ${userEnumTypes.contains(
					it.postgresType)})\n")
		}
	}

	fun castAsNecessary(value: String, type: String): String {
		if (listOf("inet", "jsonb").contains(type) || userEnumTypes.contains(type)) {
			return "CAST ($value AS $type)"
		} else {
			return value
		}
	}

	private fun mapPostgresType(postgresType: String): String {
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
			"json" -> Json::class
			"jsonb" -> Json::class
			"text" -> String::class
			"timestamp" -> if (settings.primitiveOnly) Long::class else Timestamp::class
			"uuid" -> if (settings.primitiveOnly) String::class else UUID::class
			else -> null
		}
		if (defaultType != null) {
			return defaultType.qualifiedName!!
		}
		if (userEnumTypes.contains(postgresType)) {
			return "${settings.enumPackage}.${underscoreToCamelCaseTypeName(postgresType)}"
		}
		if (postgresType.startsWith("_")) {
			val arrayType = mapPostgresType(postgresType.substring(startIndex = 1))
			return "kotlin.collections.List<$arrayType>"
		}
		throw IllegalStateException("Unknown postgres type $postgresType")
	}

}

//Format is TypeName
private fun underscoreToCamelCaseTypeName(name: String): String {
	return name.split("_").map(String::capitalize).joinToString("")
}

//Format is memberName
private fun underscoreToCamelCaseMemberName(name: String): String {
	return name.split("_").mapIndexed({ index, it -> if (index > 0) it.capitalize() else it }).joinToString("")
}