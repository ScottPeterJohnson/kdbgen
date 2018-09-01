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
   main = "net.justmachinery.kdbgen.generation.GeneratePostgresInterfaceKt"
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
the `--useCommonTypes` flag should be enabled if any of your columns are timestamps or UUIDs, the `--outputDirectory` should point to the shared project's generated sources,
and `--dslDirectory` should be set to the JVM project's generated sources. 

### Basic example

 Assume a basic users table with mandatory "uid", "email", and optional "name" fields.
 
#### Setup
```kotlin
//Any method of getting a connection will suffice. Using basic JDBC:
val connection = DriverManager.getConnection(DATABASE_URL, Properties()) as PgConnection
//A basic wrapper for obtaining a connection, whether through threadpool or just reusing the same connection
val connectionProvider = object : ConnectionProvider() {
    override fun getConnection(): Connection {
        return connection
    }
}
//This will let us write nice DSL
fun sql(cb : ConnectionProvider.()->Unit){
    cb(connectionProvider)
}
```

#### Select user
```kotlin
sql {
    //Find the user with uid "test". This will return a convenience data class containing all columns.
    usersTable.select {
        where {
            uid equalTo "test"
        }
        returning(`*`)
    }.value()
    
    //Find the emails of all users named "John Smith". This will return just the email column.
    val email = usersTable.select { 
        where { name equalTo "John Smith" }
        returning(email)
    }.values()
    
    //Both email and user ID, wrapped in a tuple like structure.
    val results = usersTable.select { returning(uid, email) }.list()
    results.map { it.first } //UID
    results.map { it.second } //Email
}
```

#### Insert user
```kotlin
sql { //Brings connection provider into scope
    usersTable.insert { 
        //Since "name" is optional, we don't have to provide it as an argument to this insert helper method.
        values(uid = "test", email = "foo@bar")
        returningNothing()    
    }.execute()
        
    //Add multiple users:
    val users = listOf("test", "test2", "test3")
    usersTable.insert {
        //Can insert using the convenience row class
        values(UsersRow(uid = "test0", email = "foo@bar.org"))
        for(user in users){
            values(uid = user, email = "$user@test.org")
        }
        returningNothing()
    }.execute()
}
```

#### Update user
```kotlin
sql {
    usersTable.update {
        name setTo "Joe Smith"
        where {
            name equalTo "test"
        }
        returning(uid)
    }.values()
}
```

### Delete user
```kotlin
sql {
    usersTable.delete {
        where {
            name equalTo "test"
        }
        returningNothing()
    }.execute()
}
```

### Join tables
```kotlin
usersTable.select {
    val addresses = join(addressTable)
    where {
        name equalTo "test"
        //The join condition
        addressId equalTo address.id
        addresses.state equalTo "CA"
    }
    returning(`*`)
}.list()

```

## Caveats
- Library syntax may change.

## TODO
- Upsert/conflict clauses
- More operations