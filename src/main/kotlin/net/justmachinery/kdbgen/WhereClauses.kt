package net.justmachinery.kdbgen

fun <S : Statement<*, T, *>, T : Table<*>>
		S.where(where : WhereInit<T>.()->Unit) : S {
	@Suppress("UNCHECKED_CAST")
	return toBase(this).copy().apply(where) as S
}

interface WhereInit<Table>{
	private fun <Value> TableColumn<Table, Value>.clause(op : String, value : Value){
		(this@WhereInit as StatementImpl<*,*,*>).addWhereClause { Pair("${this.name} $op ${this.asParameter(it)}", value as Any) }
	}

	infix fun <Value> TableColumn<Table, Value>.equalTo(value : Value) = clause("=", value)
	infix fun <Value> TableColumn<Table, Value>.notEqualTo(value : Value) = clause("!=", value)
	infix fun <Value> TableColumn<Table, Value>.greaterThan(value : Value) = clause(">", value)
	infix fun <Value> TableColumn<Table, Value>.greaterThanOrEqualTo(value : Value) = clause(">=", value)
	infix fun <Value> TableColumn<Table, Value>.lessThan(value : Value) = clause("<", value)
	infix fun <Value> TableColumn<Table, Value>.lessThanOrEqualTo(value : Value) = clause("<=", value)
}