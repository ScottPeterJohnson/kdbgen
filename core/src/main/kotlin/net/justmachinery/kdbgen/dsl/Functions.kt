package net.justmachinery.kdbgen.dsl

import java.sql.Timestamp

fun SqlDslBase.now() : Expression<Timestamp> = Expression.callFunction("now")