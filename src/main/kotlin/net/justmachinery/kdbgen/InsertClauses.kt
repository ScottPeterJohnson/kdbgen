package net.justmachinery.kdbgen


interface InsertInit<Table>

@Suppress("UNCHECKED_CAST")
fun <S : Statement<NotProvided, T, NotProvided>, T : Table<*>>
		S.insert(init : InsertInit<T>.()-> ColumnsToValues<T>) : Statement<Insert, T, NotProvided> {
	return toBase(this).copy().apply { type = StatementType.INSERT; setValues = init() } as Statement<Insert, T, NotProvided>
}

sealed class InsertValue<T> {
	class None<T> : InsertValue<T>()
	data class RawValue<T>(val value : T) : InsertValue<T>()
}