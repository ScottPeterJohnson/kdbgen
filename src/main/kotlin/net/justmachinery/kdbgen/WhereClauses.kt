package net.justmachinery.kdbgen

fun <S : Statement<*, T, *>, T : Table<*>>
		S.where(where : WhereInit<T>.(table : T)->Unit) : S {
	@Suppress("UNCHECKED_CAST")
	return toBase(this).copy().apply({ where(this.on) } ) as S
}

interface WhereInit<Table>{
	private fun impl() = (this as StatementImpl<*,*,*>)
	private fun <Value> TableColumn<Table,Value>.columnClause(clause : String, values: Value){
		impl().addWhereClause(clause, this.rawType, values)
	}
	private fun <Value> TableColumn<Table, Value>.opClause(op : String, value : Value){
		this.columnClause("${this.name} $op ${this.asParameter(value)}", value)
	}

	infix fun <Value> TableColumn<Table, Value>.equalTo(value : Value) = opClause("=", value)
	infix fun <Value> TableColumn<Table, Value>.notEqualTo(value : Value) = opClause("!=", value)
	infix fun <Value> TableColumn<Table, Value>.greaterThan(value : Value) = opClause(">", value)
	infix fun <Value> TableColumn<Table, Value>.greaterThanOrEqualTo(value : Value) = opClause(">=", value)
	infix fun <Value> TableColumn<Table, Value>.lessThan(value : Value) = opClause("<", value)
	infix fun <Value> TableColumn<Table, Value>.lessThanOrEqualTo(value : Value) = opClause("<=", value)
	infix fun <Value> TableColumn<Table, Value>.within(values : List<Value>) = impl().addWhereClause("${this.name} = ANY(?)", this.rawType.plus("[]"), values)

}