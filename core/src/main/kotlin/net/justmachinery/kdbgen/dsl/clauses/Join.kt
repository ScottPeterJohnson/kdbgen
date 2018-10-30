package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.Table

fun <Columns> CanHaveWhereStatement.join(table : Table<Columns>) : Columns {
	addJoinTable(table)
	return table.columns
}