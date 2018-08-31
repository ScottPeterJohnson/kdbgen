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
		dslWriter.run {
			line("@file:Suppress(\"UNCHECKED_CAST\", \"unused\", \"PropertyName\", \"SimplifiableCallChain\")")
			line("package ${settings.dataPackage}")
			renderImports(dslWriter)

			renderRowClass(settings, dslWriter)


			line("class $tableClassName internal constructor() : Table<$tableClassName.Columns> {")
			indent {
				line("override val `table name` = \"${type.rawName}\"")
				line("override fun columns() = Columns()")
				renderColumnDefinitions(dslWriter)

				renderSelectAll(dslWriter)

				renderInsertHelper(dslWriter)

				renderUpdateHelper(dslWriter)

				renderDeleteHelper(dslWriter)

			}
			line("}")

			line("val ${type.memberName}Table = ${type.className}Table()")

			writer.close()
		}
	}


	private fun renderImports(writer: SourceWriter) {
		writer.run {
			line("import $kdbGen.dsl.*")
			line("import $kdbGen.dsl.clauses.*")
			line("import kotlin.reflect.*")
			line("import kotlin.reflect.full.*")
		}
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
		writer.run {
			line("open class Columns {")
			indent {
				line("private companion object {")
				indent {
					type.postgresTableColumns.forEach {
						line("private val ${it.memberName} = TableColumn<${context.run { it.kotlinType }}>(\"${it.rawName}\", \"${it.postgresType}\", ${it.postgresType in context.postgresTypeToEnum}, ${context.run { it.kotlinKType }})")
					}
					line("""
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
				line("}")
				type.postgresTableColumns.forEach {
					line("val ${it.memberName} get() = Columns.${it.memberName}")
				}
				line("val `*` get() = Columns.`*`")

			}
			line("}")
		}
	}

	private fun renderSelectAll(dslWriter: SourceWriter) {
		dslWriter.line("inline fun <reified Result : ResultTuple> select(cb : SelectDsl.()->ReturnValues<Result>) = statement { cb(SelectDsl(this)) }")
		dslWriter.line("class SelectDsl(val builder : StatementBuilder) : Columns(), SelectStatementBuilder by builder")
	}

	//Safe insert helper
	private fun renderInsertHelper(dslWriter: SourceWriter) {
		dslWriter.run {
			line("inline fun <reified Result : ResultTuple> insert(cb : InsertDsl.()->ReturnValues<Result>) = statement { cb(InsertDsl(this)) }")
			line("class InsertDsl(val builder : StatementBuilder) : Columns(), InsertStatementBuilder by builder {")
			indent {
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

				line("fun values($parameterList) {")
				indent {
					line("val values = mutableListOf<Pair<TableColumn<*>, Any?>>()")
					line(valuesConstruction)
					line("addInsertValues(values)")
				}
				line("}")

				line("fun values(row : $rowName) {")
				indent {
					line("addInsertValues(listOf(${type.postgresTableColumns.joinToString(", ") { "Pair(this.${it.memberName}, row.${it.memberName})" } }))")
				}
				line("}")
			}
			line("}")
		}
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
