package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Table

interface CanHaveJoins {
	fun addJoinTable(table : Table<*>)
	fun <Columns> join(table : Table<Columns>) : Columns {
		addJoinTable(table)
		return table.columns
	}
}