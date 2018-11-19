[ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen-core/_latestVersion)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
 
 Experimental library for generating Kotlin code to interact with a Postgres database. 
 Currently supports two methods:
  - Arbitrary SQL transformed into Kotlin functions
  - A somewhat limited DSL generator
 
 The arbitrary SQL method is probably the far more powerful and elegant. The DSL generation may be repurposed or scrapped.
 
 The basic philosophy is:
 - The database should be the source of truth for Kotlin objects representing its rows, so those should be generated automatically.
 - Queries should be known safe at compile time.
 - SQL is a fine language for writing SQL.
 
 Warning: Contains annotation processors that rely on having a local Postgres 
 database with your schema in it to compile. It is this library's opinion that
 this is natural and laudable and not a big deal, but this may be a controversial stance.
  
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

If DSL generation is desired, add:
```kotlin
@GeneratePostgresInterface
```
You will need to replace:
- `DATABASE`, `DATABASE_USER`, `DATABASE_PASSWORD` with an accessible database/user/password (a local one, probably)
- `<VERSION>` with the latest version of this repository (currently [ ![Download](https://api.bintray.com/packages/scottpjohnson/generic/kdbgen/images/download.svg) ](https://bintray.com/scottpjohnson/generic/kdbgen-core/_latestVersion))

Other configuration options are available on as properties on the Settings annotation.

### Setup
```kotlin
//Any method of getting a connection will suffice. Using basic JDBC:
val connectionProvider = object : ConnectionProvider {
    //A connection provider is just a basic wrapper for obtaining a connection.
    //For efficiency, you probably want a connection pool.
    override fun getConnection(): Connection {
        return DriverManager.getConnection(DATABASE_URL, Properties()) as PgConnection
    }
}
//This will bring relevant functions into scope
fun sql(cb : ConnectionProvider.()->Unit){
    cb(connectionProvider)
}
```

### Usage (SQL Query)
```kotlin
@SqlQuery("addition",
    //The following comment allows for Intellij to highlight syntax:
    //language=PostgreSQL
	"""SELECT 1 + :addendum AS foobar"""
)
val foo = 3 
//It doesn't really matter _what_ you annotate.
//But for annotation processor reasons, it has to be a class or property.


fun test(){
    sql {
        addition(addendum = 3).first().foobar //4
    }
}

```
That's pretty much it. Write any SQL query your database supports.
kdbgen will turn it into a function that accepts named parameters of the proper types,
and returns a data class with all of the returned columns.

### Usage (DSL)

You can probably ignore the DSL. It doesn't support many Postgres features.

 Assume a basic users table with mandatory "uid", "email_address", and optional "name" fields.
 

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

## Configuration
### Multiplatform Kotlin
To use the generated DSL convenience row types in a multiplatform Kotlin project (JS + JVM + Shared code), kdbgen should be a dependency of the JVM project, 
the `useCommonTypes` flag should be enabled if any of your columns are timestamps or UUIDs, the `--outputDirectory` should point to the shared project's generated sources,
and `dslDirectory` should be set to the JVM project's generated sources. 

### Serialization
Add `dataAnnotation=["kotlinx.serialization.Serializable"]`, to use generated classes with 
with kotlinx-serialization.


## Caveats
- Library syntax may change.

## TODO
- Transforming result sets could be a little more efficient
- This approach could probably work with arbitrary SQL providers, not just Postgres. 