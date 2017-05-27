[ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen/_latestVersion)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub release](https://img.shields.io/github/release/qubyte/rubidium.svg)]()
 
 Experimental library for generating Kotlin DSL to interact with a Postgres database. 
 Currently supports basic select/insert/update/delete operations, and will map custom Postgres enum types.
 
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
   args '--beanPackage=some.company.database.tables'
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

A bunch of "TableRow" classes will be generated. These allow you to construct basic type-safe SQL queries.
For example, suppose we have a "Users" table with a "uid", "email", and an optional "name".
These will *only generate a SQL/parameter object*. Actually executing these queries is up to you.

A reflection-based `dataClassMapper()` is provided for convenience. It can translate result sets into data classes. 

To actually use this, then, you'll probably want to write helpers like the following:
 ```kotlin
 inline fun <reified Result : Any> Operation<Result>.go(): List<Result> {
 	return sql.select(this.sql, this.parameters, mapper = dataClassMapper(Result::class))
 }
 ```
 
 With that in mind:

#### Insert user
```kotlin
//Since "name" is optional, we don't have to provide it here.
UsersRow.insert(uid = "test", email = "foo@bar").go()
```

#### Select user
```kotlin
//Find the user with uid "test"
UsersRow.select(uid = "test").go().firstOrNull()
//Find all users named "John Smith"
UsersRow.select(name = "John Smith").go()
```

#### Update user
```kotlin
UsersRow.update(name = "Joe Smith").where(whereName = "test").go()
```

## Caveats
- Null is used as "not provided" but can also be a valid database value- so this will work poorly with "null" columns

## TODO
- Upsert
- More operation fluency (can currently only compare equality)