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

val kdbGen = "net.justmachinery.kdbgen"

val defaultOutputDirectory = "build/generated-sources/kotlin"
val defaultEnumPackage = "net.justmachinery.kdbgen.enums"
val defaultTablePackage = "net.justmachinery.kdbgen.tables"

class Settings(parser : ArgParser){
	val databaseUrl by parser.storing("URL of database to connect to (including user/pass)")
	val outputDirectory by parser.storing("Directory to output generated source files to").default(defaultOutputDirectory)
	val enumPackage by parser.storing("Package to output enum classes to").default(defaultEnumPackage)
	val tablePackage by parser.storing("Package to output beans to").default(defaultTablePackage)
	fun enumDirectory() : String = Paths.get(outputDirectory, enumPackage.replace(".", "/")).toString()
	fun tableDirectory() : String = Paths.get(outputDirectory, tablePackage.replace(".", "/")).toString()
}

fun main(args : Array<String>){
	val settings = Settings(ArgParser(args))
	run(settings)
}

fun run(settings : Settings){
	File(settings.enumDirectory()).deleteRecursively()
	File(settings.tableDirectory()).deleteRecursively()

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
		val properties = mutableListOf<GeneratedProperty>()

		val columnsResultSet = connection.metaData.getColumns(null, null, rawTableName, null)
		while(columnsResultSet.next()){
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
	for(type in types){
		renderer.renderType(type)
	}
}

data class GeneratedType(val rawName : String, val generatedProperties: List<GeneratedProperty>) {
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}
data class GeneratedProperty(val rawName : String, val postgresType : String, val nullable : Boolean, val defaultable : Boolean){
	val className = underscoreToCamelCaseTypeName(rawName)
	val memberName = underscoreToCamelCaseMemberName(rawName)
}

fun String.onlyWhen(condition : Boolean) : String {
	return if(condition){ this } else { "" }
}

class Renderer(val settings : Settings, val userEnumTypes : List<String>) {
	private val GeneratedProperty.kotlinType
		get() = mapPostgresType(this.postgresType).plus("?".onlyWhen(this.nullable))

	fun renderType(type: GeneratedType) {
		val kotlinPath = "${settings.tableDirectory()}/${type.className}.kt"
		File(kotlinPath).let {
			it.parentFile.mkdirs()
			it.createNewFile()
		}
		val writer = OutputStreamWriter(FileOutputStream(kotlinPath))
		writer.append("package ${settings.tablePackage}\n")
		renderImports(writer)

		renderRowClass(type, writer)

		val tableClassName = "${type.className}Table"
		writer.append("class $tableClassName : Table<${type.className}Row> {\n")
			writer.append("\toverride val name = \"${type.rawName}\"\n")
			renderColumnDefinitions(type, writer)
		writer.append("}\n")
		writer.append("val ${type.memberName} = ${type.className}Table()\n")

		//Insert DSL support
		val insertClassName = "${type.className}TableInsert"
		writer.append("data class $insertClassName<${type.generatedProperties.map{it.className}.joinToString(", ")}>(\n")
		writer.append(type.generatedProperties.map {
			"\tval _${it.memberName} : NullHolder<${it.kotlinType}>? = null"
		}.joinToString(",\n"))
		writer.append("\n)\n")
		writer.append("{\n")
			writer.append("fun toCols() : List<Pair<TableColumn<$tableClassName, Any>,Any>> {\n")
				writer.append("\treturn listOf(\n")
					writer.append(type.generatedProperties.map{
					"\t\t_${it.memberName}?.let { Pair($tableClassName.${it.memberName} as TableColumn<$tableClassName,Any>, it.value as Any) }"
					}.joinToString(",\n"))
				writer.append("\t).filterNotNull()\n")
			writer.append("}\n")
		writer.append("}\n")

		val notProvided = type.generatedProperties.map{ if(it.defaultable) "Provided" else "NotProvided"}.joinToString(",")
		val provided = type.generatedProperties.map{"Provided"}.joinToString(",")
		writer.append("fun InsertInit<$tableClassName>.values(vals : ($insertClassName<$notProvided>)->$insertClassName<$provided>) : ColumnsToValues<$tableClassName> {\n")
		writer.append("\tval insert = vals($insertClassName())\n")
		writer.append("\treturn insert.toCols()\n")
		writer.append("}\n")


		type.generatedProperties.forEachIndexed { index, it ->
			val parameters = (0..(type.generatedProperties.size - 2)).map { "P$it" }
			val parameterization = { type : String ->
				val list = parameters.toMutableList()
				list.add(index, type)
				list.joinToString(",")
			}
			val inputType = "$insertClassName<${parameterization(if(it.defaultable) "*" else "NotProvided")}>"
			val resultType = "$insertClassName<${parameterization("Provided")}>"
			val paramStr = if(parameters.isNotEmpty()) "<${parameters.joinToString(",")}>" else ""
			writer.append("fun $paramStr $inputType.${it.memberName}(${it.memberName} : ${it.kotlinType}) : $resultType = this.copy(_${it.memberName} = NullHolder(${it.memberName})) as $resultType\n")
		}
		writer.close()
	}

	fun renderRowClass(type: GeneratedType, writer : OutputStreamWriter){
		writer.append("data class ${type.className}Row(")
		writer.append(type.generatedProperties.map({
			"val ${it.memberName} : ${it.kotlinType}${"?".onlyWhen(it.nullable)}"
		}).joinToString(", "))
		writer.append(") : SqlResult\n")
	}

	fun renderImports(writer: OutputStreamWriter) {
		writer.append("import $kdbGen.*\n")
		writer.append("import javax.annotation.CheckReturnValue\n")
		writer.append("import java.sql.ResultSet\n")
	}

	fun renderColumnDefinitions(type: GeneratedType, writer: OutputStreamWriter) {
		writer.append("\tcompanion object { \n")
		type.generatedProperties.forEach {
			writer.append("\t\tval ${it.memberName} = TableColumn<${type.className}Table, ${it.kotlinType}>(\"${it.rawName}\", \"${it.postgresType}\", ${userEnumTypes.contains(it.postgresType)})\n")
		}
		writer.append("\t }\n")
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
			"json" -> Json::class
			"jsonb" -> Json::class
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