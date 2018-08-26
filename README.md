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

### Multiplatform Kotlin
To use this in a multiplatform Kotlin project (JS + JVM + Shared code), kdbgen should be a dependency of the JVM project, 
the `--primitiveOnly` flag should be enabled, the `--outputDirectory` should point to the shared project's generated sources,
and `--dslDirectory` should be set to the JVM project's generated sources. 

### Basic example

 Assume a basic users table with mandatory "uid", "email", and optional "name" fields. You can then do the following:

#### Insert user
```kotlin
//Inserts use a chained method call style to ensure that you have provided every non-defaultable field
//Since "name" is optional, we don't have to provide it here.
into(usersTable).insert { values { it
    .uid("test")
    .email("foo@bar")
    //.name("John Foo")
} }.execute(connection)
//If you omit either UID or email, the query will fail to typecheck.
//Insert many users:
val users = listOf("test", "test2", "test3")
into(usersTable).insert { values(users.map { user -> { it : UsersTableInsertInit -> it.uid(user).email("$user@test.org") }}) }.execute(connection)
//The explicit "UsersTableInsertInit" parameter is necessary due to Kotlin's limited type inference
```

#### Select user
```kotlin
//Find the user with uid "test". This will return a convenience data class containing all columns.
from(usersTable).selectAll().where { uid equalTo "test" }.execute(connection).firstOrNull()
//Find the emails of all users named "John Smith". This will return just the email column wrapped in a tuple-like data structure.
val (email) = from(usersTable).select(usersTable.email).where { name equalTo "John Smith" }.execute(connection).first()
```

#### Update user
```kotlin
//Support for returning from updates/inserts/deletes.
val (uid) = from(usersTable).update { it.name setTo "Joe Smith" }.where { it.name equalTo "test" }.returning(uid).execute(connection).first()
```

## Caveats
- Library syntax may change.

## Array Types
- These will convert to/from lists

## TODO
- Queries on more than one table
- Upsert
- Conflict clauses
- More operations