[ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen/_latestVersion)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub release](https://img.shields.io/github/release/qubyte/rubidium.svg)]()
 
 Experimental library for generating Kotlin DSL to interact with a Postgres database. 
 Currently supports:
  - Basic select/insert/update/delete operations on a single table
  - Custom Postgres enum types
 
 The basic philosophy is:
 - The database should be the source of truth for Kotlin objects representing its rows, so those should be generated automatically.
 - Queries should be as type-safe as possible, which can be accomplished with code generation.
  
## Use
### Install
Add the following to your build.gradle where appropriate:
```groovy
repositories {
jcenter()
maven { url 'https://dl.bintray.com/scottpjohnson/generic/' }
}
task generatePostgresInterface(type : JavaExec){
   classpath = sourceSets.main.compileClasspath
   main = "net.justmachinery.kdbgen.GeneratePostgresInterfaceKt"
   args '--databaseUrl=jdbc:postgresql://localhost:5432/DATABASE?user=DATABASE_USER&password=DATABASE_PASSWORD'
   args '--enumPackage=some.company.database.enums'
   args '--tablePackage=some.company.database.tables'
   args '--outputDirectory=build/generated-sources/kotlin'
}
compileKotlin.dependsOn('generatePostgresInterface')
sourceSets {
   main.kotlin.srcDirs += "build/generated-sources/kotlin"
}
dependencies {
    compile 'net.justmachinery.kdbgen:kdbgen:<VERSION>'
}
```
You will need to replace:
- `DATABASE`, `DATABASE_USER`, `DATABASE_PASSWORD` with an accessible database/user/password (a local one, probably)
- `some.company.database.enums` and `some.company.database.tables` with the packages to output generated classes to
- Optional: `build/generated-sources/kotlin` with wherever your generated sources go
- `<VERSION>` with the latest version of this repository

### Basic example

This library will *only generate an object containing SQL and named parameters*. Actually executing these queries is up to you, so some assembly is required.

A reflection-based `resultMapper()` is provided for convenience. It can translate result sets into data classes. 

The following example code is for interfacing with the [kwery-core](https://github.com/andrewoma/kwery/tree/master/core) library:
 ```kotlin
 fun <Data : SqlResult> dataClassMapper(dataClass: KClass<Data>): (Row) -> Data {
 	val mapper = resultMapper(dataClass)
 	return {
 		mapper.invoke(it.resultSet)
 	}
 }
 
 fun <Op : SqlOp, On : OnTarget> Statement<Op, On, NotProvided>.execute(): Unit {
 	val (sql, parameters) = render(this)
 	session.update(sql, parameters.toMap())
 }
 
 inline fun <Op : SqlOp, On : OnTarget, reified Result : SqlResult> Statement<Op, On, Result>.query(): List<Result> {
 	val (sql, parameters) = render(this)
 	return session.select(sql, parameters.toMap(), mapper = dataClassMapper(Result::class))
 }
 ```
 
 Assume a basic users table with mandatory "uid", "email", and optional "name" fields. You can then do the following:

#### Insert user
```kotlin
//Inserts use a chained method call style to ensure that you have provided every non-defaultable field
//Since "name" is optional, we don't have to provide it here.
into(usersRow).insert { values { it
    .uid("test")
    .email("foo@bar")
    //.name("John Foo")
} }.execute()
//If you omit either UID or email, the query will fail to typecheck.
```

#### Select user
```kotlin
//Find the user with uid "test". This will return a convenience data class containing all columns.
from(users).selectAll().where { uid equalTo "test" }.query().firstOrNull()
//Find the emails of all users named "John Smith". This will return just the email column.
from(users).select(email).where { name equalTo "John Smith" }.query()
```

#### Update user
```kotlin
//Support for returning from updates/inserts/deletes.
from(users).update { name to "Joe Smith" }.where { name equalTo "test" }.returning(uid).query()
```

## Caveats
- Library syntax may change.
- Lots of imports required.

## TODO
- Queries on more than one table
- Upsert
- Conflict clauses
- More operations