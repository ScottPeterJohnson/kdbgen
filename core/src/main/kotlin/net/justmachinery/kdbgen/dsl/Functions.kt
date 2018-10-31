package net.justmachinery.kdbgen.dsl

import java.sql.Timestamp

fun SqlDslBase.now() : Expression<Timestamp> = Expression.callFunction("now")

operator fun <T : Number> Expression<T>.plus(other : Expression<T>) : Expression<T> = Expression.callOperator("+", this, other)
operator fun <T : Number> Expression<T>.minus(other : Expression<T>) : Expression<T> = Expression.callOperator("-", this, other)
operator fun <T : Number> Expression<T>.times(other : Expression<T>) : Expression<T> = Expression.callOperator("*", this, other)
operator fun <T : Number> Expression<T>.div(other : Expression<T>) : Expression<T> = Expression.callOperator("/", this, other)
operator fun <T : Number> Expression<T>.rem(other : Expression<T>) : Expression<T> = Expression.callOperator("%", this, other)