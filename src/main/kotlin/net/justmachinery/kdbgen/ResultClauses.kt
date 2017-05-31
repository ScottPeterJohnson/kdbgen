package net.justmachinery.kdbgen

/**
 * Result data tuples
 */
interface SqlResult
interface ResultTuple : SqlResult
data class Result1<out V1>(val first : V1) : ResultTuple
data class Result2<out V1, out V2>(val first : V1, val second : V2) : ResultTuple
data class Result3<out V1, out V2, out V3>(val first : V1, val second : V2, val third : V3) : ResultTuple
data class Result4<out V1, out V2, out V3, out V4>(val first : V1, val second : V2, val third : V3, val fourth : V4) : ResultTuple
data class Result5<out V1, out V2, out V3, out V4, out V5>(val first : V1, val second : V2, val third : V3, val fourth : V4, val fifth : V5) : ResultTuple
data class Result6<out V1, out V2, out V3, out V4, out V5, out V6>(val first : V1, val second : V2, val third : V3, val fourth : V4, val fifth : V5, val sixth : V6) : ResultTuple


fun <S : Statement<NotProvided, T, NotProvided>, T : Table<DataRow>, DataRow> S.selectAll() : Statement<Select, T, DataRow> {
	return addSelects(this, null)
}

fun <S : Statement<NotProvided, T, NotProvided>, T : Table<*>, Column : TableColumn<T,V1>, V1>
		S.select(column : Column) : Statement<Select, T, Result1<V1>> {
	return addSelects(this, listOf(column))
}
fun <S : Statement<NotProvided, T, NotProvided>, T : Table<*>, Column : TableColumn<T,V1>, V1, Column2 : TableColumn<T,V2>, V2>
		S.select(column : Column, column2 : Column2) : Statement<Select, T, Result2<V1,V2>> {
	return addSelects(this, listOf(column, column2))
}


fun <S : Statement<Operation,T,NotProvided>, Operation, T : Table<DataRow>, DataRow>
		S.returningAll() : Statement<Operation, T, DataRow>
{
	return addReturns(this, emptyList())
}

fun <S : Statement<Operation,T,NotProvided>, Operation, T : Table<*>,
		Column : TableColumn<T, V1>, V1>
		S.returning(column : Column) : Statement<Operation, T, Result1<V1>>
{
	return addReturns(this, listOf(column))
}
fun <S : Statement<Operation,T,NotProvided>, Operation, T : Table<*>,
		Column : TableColumn<T, V1>, V1, Column2 : TableColumn<T, V2>, V2>
		S.returning(column : Column, column2: Column2) : Statement<Operation, T, Result2<V1,V2>>
{
	return addReturns(this, listOf(column, column2))
}

fun <S : Statement<Operation,T,NotProvided>, Operation, T : Table<*>,
		Column : TableColumn<T, V1>, V1, Column2 : TableColumn<T, V2>, V2, Column3 : TableColumn<T,V3>, V3>
		S.returning(column : Column, column2: Column2, column3 : Column3) : Statement<Operation, T, Result3<V1,V2,V3>>
{
	return addReturns(this, listOf(column, column2, column3))
}

private fun <On, Returning> addSelects(s : Statement<NotProvided, On, NotProvided>, selects : List<TableColumn<*,*>>?) : Statement<Select, On, Returning> {
	@Suppress("UNCHECKED_CAST")
	return toBase(s).copy().apply { type = StatementType.SELECT; selectColumns = selects } as Statement<Select, On,Returning>
}
private fun <Operation, On, Returning> addReturns(s : Statement<Operation, On, NotProvided>, returns : List<TableColumn<*,*>>?) : Statement<Operation, On, Returning> {
	@Suppress("UNCHECKED_CAST")
	return toBase(s).copy().apply { selectColumns = returns } as Statement<Operation, On, Returning>
}