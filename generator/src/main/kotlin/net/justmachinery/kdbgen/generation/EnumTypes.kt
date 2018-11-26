package net.justmachinery.kdbgen.generation

import org.postgresql.jdbc.PgConnection
import java.io.File

data class EnumType(val className : String, val postgresName : String, val values: List<String>)

fun generateEnumTypes(connection: PgConnection): List<EnumType> {
	val userEnumTypes = mutableListOf<EnumType>()
	val userTypesResultSet = connection.prepareStatement("SELECT oid, typname FROM pg_type " + "WHERE typcategory = 'E'").executeQuery()
	while (userTypesResultSet.next()) {
		val postgresName = userTypesResultSet.getString("typname")
		val className = underscoreToCamelCaseTypeName(postgresName)
		val id = userTypesResultSet.getInt("oid")
		val values = mutableListOf<String>()
		val valuesRs = connection.prepareStatement("SELECT * FROM pg_enum WHERE enumtypid = $id order by enumsortorder asc").executeQuery()
		while (valuesRs.next()) {
			values.add(valuesRs.getString("enumlabel"))
		}

		userEnumTypes.add(EnumType(className, postgresName, values))
	}
	return userEnumTypes
}

fun renderEnumType(settings : Settings, type : EnumType){
	val output = File("${settings.outputDirectory}/${type.className}.kt")
	output.parentFile.mkdirs()
	output.createNewFile()
	output.writeText("package ${settings.outputPackage};\nenum class ${type.className} { ${type.values.joinToString(", ")} }")
}