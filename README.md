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
@GeneratePostgresInterface(
	databaseUrl = "jdbc:postgresql://localhost:5432/DATABASE?user=DATABASE_USER&password=DATABASE_PASSWORD"
)
private class GeneratePostgres
```
You will need to replace:
- `DATABASE`, `DATABASE_USER`, `DATABASE_PASSWORD` with an accessible database/user/password (a local one, probably)
- `<VERSION>` with the latest version of this repository

Other configuration options are available on the GeneratePostgresInterface annotation.

### Multiplatform Kotlin
To use the generated convenience row types in a multiplatform Kotlin project (JS + JVM + Shared code), kdbgen should be a dependency of the JVM project, 
the `useCommonTypes` flag should be enabled if any of your columns are timestamps or UUIDs, the `--outputDirectory` should point to the shared project's generated sources,
and `dslDirectory` should be set to the JVM project's generated sources. 

### Serialization
Add `dataAnnotation=["kotlinx.serialization.Serializable"]`, to use generated classes with 
with kotlinx-serialization.

### Basic examples

 Assume a basic users table with mandatory "uid", "email_address", and optional "name" fields.
 
#### Setup
```kotlin
//Any method of getting a connection will suffice. Using basic JDBC:
val connection = DriverManager.getConnection(DATABASE_URL, Properties()) as PgConnection
//A basic wrapper for obtaining a connection, whether through threadpool or just reusing the same connection
val connectionProvider = object : ConnectionProvider {
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
    //Find the user named "test". This will return a convenience data class containing all columns.
    usersTable.select {
        where {
            userName equalTo parameter("test")
        }
        returning(`*`)
    }.value()

    //Find the emails of all users named "John Smith". This will return just the email column.
    val email = usersTable.select {
        where { userName equalTo parameter("John Smith") }
        returning(emailAddress)
    }.values()

    //Both email and user ID, wrapped in a tuple like structure.
    val results = usersTable.select { returning(userId, emailAddress) }.list()
    results.map { it.first } //UID
    results.map { it.second } //Email
}
```

#### Insert user
```kotlin
sql {
    usersTable.insert {
        //Since "name" is optional, we don't have to provide it as an argument to this insert helper method.
        values(userName = "test", emailAddress = "foo@bar")
        returningNothing()
    }.execute()

    //Add multiple users:
    val users = listOf("test", "test2", "test3")
    usersTable.insert {
        //Can insert using the convenience row class
        //(Generated values must be supplied if manually constructed)
        values(UsersRow(userName = "test0", emailAddress = "foo@bar.org", userId = 2, addressId = null))
        for (user in users) {
            values(userName = user, emailAddress = "$user@test.org")
        }
        returningNothing()
    }.execute()
}
```

#### Update user
```kotlin
sql {

    usersTable.update {
        userName setTo parameter("Joe Smith")
        where {
            userName equalTo parameter("test")
        }
        returning(userId)
    }.values()
}
```

#### Delete user
```kotlin
sql {

    usersTable.delete {
        where {
            userName equalTo parameter("test")
        }
        returningNothing()
    }.execute()
}
```

#### Join tables
```kotlin
sql {

    usersTable.select {
        val addresses = join(addressesTable)
        where {
            userName equalTo parameter("test")
            //The join condition
            addressId equalTo addresses.addressId
            addresses.state equalTo parameter("CA")
        }
        returning(`*`)
    }.list()

}
```

#### Upsert / Conflict Clause
```kotlin
sql {
    usersTable.insert {
        values(userName = "John Smith", emailAddress = "foo@bar.com")
        //Currently only supports column inferred constraints
        onConflictDoUpdate(userName){ excluded ->
            emailAddress setTo excluded.emailAddress
        }
        returningNothing()
    }
}

```

## Caveats
- Library syntax may change.

## TODO
- More operations