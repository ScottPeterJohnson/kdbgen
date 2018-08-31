package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.utility.onlyWhen
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

internal class DslRenderer(
		private val type: PostgresTable,
		private val context: RenderingContext
) {
	private val settings = context.settings
	private fun getOutputStream(directory: String): OutputStreamWriter {
		val fullPath = "$directory/${type.className}.kt"
		File(fullPath).let {
			it.parentFile.mkdirs()
			it.createNewFile()
		}
		return OutputStreamWriter(FileOutputStream(fullPath))
	}

	private val tableClassName = "${type.className}Table"

	fun render() {
		val dslWriter = getOutputStream(settings.dslDirectory())

		dslWriter.append("@file:Suppress(\"UNCHECKED_CAST\", \"unused\", \"PropertyName\", \"SimplifiableCallChain\")\n")
		dslWriter.append("package ${settings.dataPackage}\n")
		renderImports(dslWriter)

		renderRowClass(settings, dslWriter)


		dslWriter.append("class $tableClassName : Table {\n")
		dslWriter.append("\toverride val `table name` = \"${type.rawName}\"\n")
		renderColumnDefinitions(dslWriter)


		renderSelectAll(dslWriter)


		renderInsertHelper(dslWriter)
		dslWriter.append("}\n")
		dslWriter.append("val ${type.memberName}Table = ${type.className}Table()\n")

		dslWriter.close()
	}

	private fun renderSelectAll(dslWriter: OutputStreamWriter) {
		dslWriter.append("""
			val `*` = DataClassSource(
				listOf(${type.postgresTableColumns.joinToString(", ") { it.memberName + ".selectSource" }})
			) {
				$rowName(${
					type.postgresTableColumns.mapIndexed { index, it ->
						"it[$index] as " + context.run { it.kotlinType }
					}.joinToString(", ")
				})
			}
		""".trimIndent().prependIndent("\t") + "\n")
	}

	//Safe insert helper
	private fun renderInsertHelper(dslWriter: OutputStreamWriter) {
		val parameterList = type.postgresTableColumns.joinToString(", ") {
			"${it.memberName} : ${context.run { it.kotlinType }}${if (it.defaultable) "${"?".onlyWhen(!it.nullable)} = null" else ""}"
		}
		val valuesConstruction = type.postgresTableColumns.joinToString("\n") {
			val insertValue = "values.add(Pair(this@$tableClassName.${it.memberName}, ${it.memberName}))"
			if (it.defaultable) {
				"""
						if(${it.memberName} != null) {
							$insertValue
						}
					""".trimIndent()
			} else {
				insertValue
			}
		}

		dslWriter.append("\tfun InsertStatementBuilder.values($parameterList) : Unit {\n")
		dslWriter.append("\t\tval values = mutableListOf<Pair<TableColumn<*>, Any?>>()\n")
		dslWriter.append(valuesConstruction.prependIndent("\t\t") + "\n")
		dslWriter.append("\t\taddInsertValues(values)\n")
		dslWriter.append("\t}\n")
	}

	private val rowName = "${type.className}Row"

	private fun renderRowClass(settings: Settings, writer: OutputStreamWriter) {
		fun writeTemplate(actualWriter: OutputStreamWriter) {
			actualWriter.append("data class $rowName(")
			actualWriter.append(type.postgresTableColumns.map {
				"val ${it.memberName} : ${context.run { it.kotlinType }}"
			}.joinToString(", "))
			actualWriter.append(")\n")
		}

		if (settings.dslDirectory() != settings.dataDirectory()) {
			val dataclassWriter = getOutputStream(settings.dataDirectory())
			dataclassWriter.append("package ${settings.dataPackage}\n")
			writeTemplate(dataclassWriter)
			dataclassWriter.close()
		} else {
			writeTemplate(writer)
		}
	}

	private fun renderImports(writer: OutputStreamWriter) {
		writer.append("import $kdbGen.dsl.*\n")
		writer.append("import $kdbGen.dsl.clauses.*\n")
		writer.append("import kotlin.reflect.*\n")
		writer.append("import kotlin.reflect.full.*\n")
	}

	private fun renderColumnDefinitions(writer: OutputStreamWriter) {
		type.postgresTableColumns.forEach {
			writer.append("\tval ${it.memberName} = TableColumn<${context.run { it.kotlinType }}>(\"${it.rawName}\", \"${it.postgresType}\", ${it.postgresType in context.postgresTypeToEnum}, ${context.run { it.kotlinKType }})\n")
		}
	}
}
