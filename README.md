[ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen-core/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen-core/_latestVersion)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
 
 Experimental library for writing Postgres-compatible SQL in Kotlin via annotation processing. 
 Basically, turns SQL statements into type-checked functions at compile time.
 
 The underlying philosophy is:
 - Databases understand their schemas already, so you shouldn't have to write more code describing your tables 
 - Queries should be known safe at compile time. (We can currently only mostly achieve this; significant holes
 include nullability on outputs, which Postgres does not return good metadata for.)
 - SQL is a fine language for writing SQL.
 
 Warning: Contains annotation processors that rely on having a local Postgres 
 database with your schema in it to compile. It is this library's opinion that
 this is natural and laudable and not a big deal, but this may not work for you.
  
## Use
### Install
Make sure you have [kapt](https://kotlinlang.org/docs/reference/kapt.html) enabled.

Add the following to your build.gradle where appropriate:
```groovy
repositories {
    jcenter()
    maven { url 'https://dl.bintray.com/scottpjohnson/generic/' }
}

dependencies {
    compile 'net.justmachinery.kdbgen:kdbgen-core:<VERSION>'
    kapt 'net.justmachinery.kdbgen:kdbgen-generator:<VERSION>'
}
```

Add the following annotation anywhere in your project:
```kotlin
@SqlGenerationSettings(
	databaseUrl = "jdbc:postgresql://localhost:5432/DATABASE?user=DATABASE_USER&password=DATABASE_PASSWORD"
)
private class GeneratePostgres
```

You will need to replace:
- `DATABASE`, `DATABASE_USER`, `DATABASE_PASSWORD` with an accessible database/user/password (a local one, probably)
- `<VERSION>` with the latest version of this repository (currently [ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen-core/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen-core/_latestVersion))

### Setup
```kotlin
//Any method of getting a connection will suffice. 
//Using basic JDBC and a helper function to bring things into scope: 
fun sql(
    //A connection provider is just a basic wrapper for obtaining a connection.
    //For efficiency, you probably want a connection pool.
    //Note that the connectionProvider does not close() the connection.
    cb : ConnectionProvider.()->Unit
){
    DriverManager.getConnection(DATABASE_URL, Properties()).use {
        cb(object : ConnectionProvider {
            override fun getConnection() = it        
        })    
    }
}
```

### Usage (SQL Query)
```kotlin
@SqlQuery("addition",
    //Intellij should highlight this with SQL syntax.
    //You may need to prompt it, e.g. with the comment line:
    //language=PostgreSQL
	"""SELECT 1 + :addendum"""
)
@SqlQuery("multipleAdditions", /* language=PostgreSQL */ 
    """
        SELECT 1 + 2 AS sum, :foo + :foo as twoFoo, now() as current_time
    """
)
val foo = 3 
//It doesn't really matter _what_ you annotate.
//But for annotation processor reasons, it has to be a class or property.


fun test(){
    sql {
        addition(addendum = 3).first() //4. Since only one column was returned, the result is List<Long>
        multipleAdditions(7).first() //A generated data class containing sum = 3, twoFoo = 14, now() = timestamp...
    }
}

//To more cleanly give your queries a class-limited scope:
@QueryContainer
//The annotation generates a ${ClassName}Queries interface that the actual class can implement,
//which gives access to the query functions defined in its body.
class QueryObject : QueryObjectQueries {
    @SqlQuery("queryImpl", "SELECT * FROM sometable")
    fun doQuery(){
        sql {
            queryImpl()
        }
    }
    //Also supported: multiple result sets in a query.
    @SqlQuery("multiResultSetImpl", "SELECT name FROM user; SELECT name FROM street", "TwoResultSets")
    fun doQuery() {
        val result : TwoResultSets = sql { multiResultSetImpl() }
        val (users, streets) = result
    }
}

```
That's pretty much it. Write any SQL query your database supports.
kdbgen will turn it into a function that accepts named parameters of the proper types,
and returns a data class with all of the returned columns.

#### Input parameter nullability
Unfortunately, Postgres does not seem to support nullability metadata on input parameter types.
For safety, kdbgen assumes all input parameters are never null. If you need nullability, add a `?`
to the end of a parameter name. For example, `select * from users where name = :name?`.

Keep in mind that nulls are special snowflakes in SQL. The above query for instance will never
return any rows when passed null.

#### Query result naming
Assign a name to the query result using the `resultName` parameter of `@SqlQuery`.
You can either use a simple name (`Foo`) and generate the wrapper class automatically,
or fully qualify the name (`com.mycompany.Foo`) to use an existing class. Said class should
have a constructor that accepts exactly the named parameters of the query.

#### Using temporary views
Views are an excellent way to simplify complex SQL code and reduce code reuse. Temporary views are those which
are created per-connection and not persisted in the database. kdbgen offers support by the `@SqlPrelude` annotation.
All `@SqlPrelude` annotations are collected together and run once before evaluating any queries; you can thus
reference temporary views created within them in your queries. A "prelude.sql" file is generated- be sure to
initialize your database connections at runtime by running its contents.

In Intellij, you can add the "prelude.sql" file as a data source to correctly resolve view references in query text.

If your views logically depend on each other, you can specify class dependencies within the annotation to control their output ordering.

BE CAREFUL to use "create temporary view" and not "create view". Otherwise, compiling will actually create the views
in the database. (This apparently can't be worked around with read-only connections.)

## TODO
- Transforming parameters/results could be optimized further
- This approach could probably work with arbitrary SQL providers, not just Postgres. 