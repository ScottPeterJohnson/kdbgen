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

	class SourceWriter(val writer : OutputStreamWriter) {
		private var indentLevel = 0
		fun indent(cb: ()->Unit){
			indentLevel += 1
			cb()
			indentLevel -= 1
		}
		fun line(text : String){
			writer.appendln(text.prependIndent("\t".repeat(indentLevel)))
		}
	}

	fun render() {
		val dslWriter = SourceWriter(getOutputStream(settings.dslDirectory()))

		dslWriter.line("@file:Suppress(\"UNCHECKED_CAST\", \"unused\", \"PropertyName\", \"SimplifiableCallChain\")")
		dslWriter.line("package ${settings.dataPackage}")
		renderImports(dslWriter)

		renderRowClass(settings, dslWriter)


		dslWriter.line("class $tableClassName internal constructor() : Table {")
		dslWriter.indent {
			dslWriter.line("\toverride val `table name` = \"${type.rawName}\"")
			renderColumnDefinitions(dslWriter)

			renderSelectAll(dslWriter)

			renderInsertHelper(dslWriter)

			renderUpdateHelper(dslWriter)

			renderDeleteHelper(dslWriter)

		}
		dslWriter.line("}")

		dslWriter.line("val ${type.memberName}Table = ${type.className}Table()")

		dslWriter.writer.close()
	}


	private fun renderImports(writer: SourceWriter) {
		writer.line("import $kdbGen.dsl.*")
		writer.line("import $kdbGen.dsl.clauses.*")
		writer.line("import kotlin.reflect.*")
		writer.line("import kotlin.reflect.full.*")
	}


	private fun renderRowClass(settings: Settings, writer: SourceWriter) {
		fun writeTemplate(actualWriter: SourceWriter) {
			actualWriter.line("data class $rowName(")
			actualWriter.indent {
				actualWriter.line(type.postgresTableColumns.map {
					"val ${it.memberName} : ${context.run { it.kotlinType }}"
				}.joinToString(",\n"))
			}
			actualWriter.line(")")
		}

		if (settings.dslDirectory() != settings.dataDirectory()) {
			val dataclassWriter = SourceWriter(getOutputStream(settings.dataDirectory()))
			dataclassWriter.line("package ${settings.dataPackage}")
			writeTemplate(dataclassWriter)
			dataclassWriter.writer.close()
		} else {
			writeTemplate(writer)
		}
	}

	private fun renderColumnDefinitions(writer: SourceWriter) {
		writer.line("open class Columns {")
		writer.indent {
			writer.line("private companion object {")
			writer.indent {
				type.postgresTableColumns.forEach {
					writer.line("private val ${it.memberName} = TableColumn<${context.run { it.kotlinType }}>(\"${it.rawName}\", \"${it.postgresType}\", ${it.postgresType in context.postgresTypeToEnum}, ${context.run { it.kotlinKType }})")
				}
				writer.line("""
					private val `*` = DataClassSource(
						listOf(${type.postgresTableColumns.joinToString(", ") { it.memberName + ".selectSource" }})
					) {
						$rowName(${
						type.postgresTableColumns.mapIndexed { index, it ->
							"it[$index] as " + context.run { it.kotlinType }
						}.joinToString(", ")
						})
					}
				""".trimIndent())
			}
			writer.line("}")
			type.postgresTableColumns.forEach {
				writer.line("val ${it.memberName} get() = Columns.${it.memberName}")
			}
			writer.line("val `*` get() = Columns.`*`")

		}
		writer.line("}")
	}

	private fun renderSelectAll(dslWriter: SourceWriter) {
		dslWriter.line("inline fun <reified Result : ResultTuple> select(cb : SelectDsl.()->ReturnValues<Result>) = statement { cb(SelectDsl(this)) }")
		dslWriter.line("class SelectDsl(val builder : StatementBuilder) : Columns(), ReturningStatementBuilder by builder")
	}

	//Safe insert helper
	private fun renderInsertHelper(dslWriter: SourceWriter) {
		dslWriter.line("inline fun <reified Result : ResultTuple> insert(cb : InsertDsl.()->ReturnValues<Result>) = statement { cb(InsertDsl(this)) }")
		dslWriter.line("class InsertDsl(val builder : StatementBuilder) : Columns(), InsertStatementBuilder by builder {")
		dslWriter.indent {
			val parameterList = type.postgresTableColumns.joinToString(", ") {
				"${it.memberName} : ${context.run { it.kotlinType }}${if (it.defaultable) "${"?".onlyWhen(!it.nullable)} = null" else ""}"
			}
			val valuesConstruction = type.postgresTableColumns.joinToString("\n") {
				val insertValue = "values.add(Pair(this.${it.memberName}, ${it.memberName}))"
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

			dslWriter.line("fun values($parameterList) : Unit {")
			dslWriter.indent {
				dslWriter.line("val values = mutableListOf<Pair<TableColumn<*>, Any?>>()")
				dslWriter.line(valuesConstruction)
				dslWriter.line("addInsertValues(values)")
			}
			dslWriter.line("}")
		}
		dslWriter.line("}")
	}

	private fun renderUpdateHelper(dslWriter : SourceWriter){
		dslWriter.line("inline fun <reified Result : ResultTuple> update(cb : UpdateDsl.()->ReturnValues<Result>) = statement { cb(UpdateDsl(this)) }")
		dslWriter.line("class UpdateDsl(val builder : StatementBuilder) : Columns(), UpdateStatementBuilder by builder")
	}

	private fun renderDeleteHelper(dslWriter : SourceWriter){
		dslWriter.line("inline fun <reified Result : ResultTuple> delete(cb : DeleteDsl.()->ReturnValues<Result>) = statement { isDelete = true; cb(DeleteDsl(this)) }")
		dslWriter.line("class DeleteDsl(val builder : StatementBuilder) : Columns(), DeleteStatementBuilder by builder")
	}

	private val rowName = "${type.className}Row"

}
