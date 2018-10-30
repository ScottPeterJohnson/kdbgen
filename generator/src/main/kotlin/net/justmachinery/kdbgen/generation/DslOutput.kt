package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.kdbGen
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


			line("class $tableClassName internal constructor(override val tableName : String = \"${type.rawName}\") : Table<$tableClassName.Columns> {")
			indent {
				line("override fun aliased(alias : String) = $tableClassName(tableName = alias)")
				line("override val columns : Columns = ColumnsImpl()")
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
			for(annotation in settings.dataAnnotation){
				actualWriter.line("@$annotation")
			}
			actualWriter.line("data class $rowName(")
			actualWriter.indent {
				actualWriter.line(type.postgresTableColumns.map {
					"${if(settings.mutableData) "var" else "val"} ${it.memberName} : ${context.run { it.kotlinType }}"
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
			line("override val columnsList = listOf(${type.postgresTableColumns.joinToString(", ") { "columns.${it.memberName}" }})")
			line("interface Columns {")
			indent {
				type.postgresTableColumns.forEach {
					line("val ${it.memberName} : TableColumn<${context.run { it.kotlinType }}>")
				}
				line("val `*` : DataClassSource<$rowName>")
			}
			line("}")
			line("internal inner class ColumnsImpl : Columns {")
			indent {
				type.postgresTableColumns.forEach {
					line("override val ${it.memberName} = TableColumn<${context.run { it.kotlinType }}>(this@$tableClassName, \"${it.rawName}\", PostgresType(${context.run { it.kotlinKType }}, \"${it.postgresType}\", ${it.postgresType in context.postgresTypeToEnum}))")
				}
				line("""
					override val `*` = DataClassSource(
						listOf(${type.postgresTableColumns.joinToString(", ") { it.memberName }})
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
		}
	}

	private fun renderSelectAll(dslWriter: SourceWriter) {
		dslWriter.line("inline fun <reified Result : ResultTuple> select(cb : SelectDsl.()->ReturnValues<Result>) = statement { cb(SelectDsl(this)) }")
		dslWriter.line("inner class SelectDsl(val builder : StatementBuilder) : Columns by columns, SelectStatementBuilder by builder")
	}

	//Safe insert helper
	private fun renderInsertHelper(dslWriter: SourceWriter) {
		dslWriter.run {
			line("inline fun <reified Result : ResultTuple> insert(cb : InsertDsl.()->ReturnValues<Result>) = statement { cb(InsertDsl(this)) }")
			line("inner class InsertDsl(val builder : StatementBuilder) : Columns by columns, InsertStatementBuilder by builder {")
			indent {
				val parameterList = type.postgresTableColumns.joinToString(", ") {
					"${it.memberName} : ${context.run { it.kotlinType }}${if (it.defaultable) "${"?".onlyWhen(!it.nullable)} = null" else ""}"
				}
				val valuesConstruction = type.postgresTableColumns.joinToString("\n") {
					val insertValue = "values.add(this.${it.memberName}.insertValue(${it.memberName}))"
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
					line("val values = mutableListOf<SqlInsertValue<*>>()")
					line(valuesConstruction)
					line("addInsertValues(values)")
				}
				line("}")

				line("fun values(row : $rowName) {")
				indent {
					line("addInsertValues(listOf(${type.postgresTableColumns.joinToString(", ") { "this.${it.memberName}.insertValue(row.${it.memberName})" } }))")
				}
				line("}")

				line("""
					fun onConflictDoUpdate(
						column : TableColumn<*>,
						vararg columns : TableColumn<*>,
						cb : ConflictUpdateBuilder.(excluded : Columns)->Unit){
						val conflicts = ConflictUpdateBuilder()
						cb(conflicts, aliased("excluded").columns)
						addConflictClause(conflicts.build(listOf(column).plus(columns)))
					}
					""".trimIndent())
			}
			line("}")
		}
	}

	private fun renderUpdateHelper(dslWriter : SourceWriter){
		dslWriter.line("inline fun <reified Result : ResultTuple> update(cb : UpdateDsl.()->ReturnValues<Result>) = statement { cb(UpdateDsl(this)) }")
		dslWriter.line("inner class UpdateDsl(val builder : StatementBuilder) : Columns by columns, UpdateStatementBuilder by builder")
	}

	private fun renderDeleteHelper(dslWriter : SourceWriter){
		dslWriter.line("inline fun <reified Result : ResultTuple> delete(cb : DeleteDsl.()->ReturnValues<Result>) = statement { isDelete = true; cb(DeleteDsl(this)) }")
		dslWriter.line("inner class DeleteDsl(val builder : StatementBuilder) : Columns by columns, DeleteStatementBuilder by builder")
	}

	private val rowName = "${type.className}Row"

}
