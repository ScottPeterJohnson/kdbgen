package net.justmachinery.kdbgen.dsl.clauses

import net.justmachinery.kdbgen.dsl.SqlDslBase

interface DeleteStatementBuilder : CanHaveReturningValue, CanHaveWhereStatement, CanHaveJoins, SqlDslBase