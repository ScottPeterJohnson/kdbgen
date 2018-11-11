package net.justmachinery.kdbgen

import io.kotlintest.matchers.beEmpty
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldNotBe
import net.justmachinery.kdbgen.common.CommonTimestamp
import net.justmachinery.kdbgen.common.CommonUUID
import net.justmachinery.kdbgen.dsl.ConnectionProvider
import net.justmachinery.kdbgen.dsl.clauses.Result1
import net.justmachinery.kdbgen.dsl.clauses.Result2
import net.justmachinery.kdbgen.dsl.parameter
import net.justmachinery.kdbgen.dsl.plus
import net.justmachinery.kdbgen.dsl.uniqueSubquery
import net.justmachinery.kdbgen.test.generated.enums.EnumTypeTest
import net.justmachinery.kdbgen.test.generated.tables.*
import org.postgresql.jdbc.PgConnection
import java.sql.Connection
import java.sql.DriverManager
import java.util.*


class BasicOperationsTest : DatabaseTest() {
	init {
		"should be able to do basic operations" {
			sql {
				usersTable.run {
					insert {
						values(userName = "Bob")
						returningNothing()
					}.execute()
					select{
						where {
							userName equalTo parameter("Bob")
						}
						returning(`*`)
					}.values() shouldNot beEmpty()
					update {
						userName setTo parameter("Joe")
						where {
							userName equalTo parameter("Bob")
						}
						returning(userName)
					}.value() shouldBe "Joe"

					val deletedEmailAddress = delete {
						where {
							userName equalTo parameter("Joe")
						}
						returning(emailAddress)
					}.value()
					deletedEmailAddress shouldBe null
					select { returning(`*`) }.values() should beEmpty()
					val users = listOf("Bob", "Frank", "Joe")
					insert {
						for(user in users){
							values(userName = user)
						}
						returningNothing()
					}.execute()

					select{
						where {
							userName within users
						}
						returning(userName)
					}.values() shouldBe users
				}
			}
		}

		"should be able to do a basic join" {
			sql {
				val enum = EnumTestRow(1, EnumTypeTest.test2)
				val user = UsersRow(userId = 1, userName = "foo", emailAddress = "foo@bar.com", addressId = null)
				usersTable.insert { values(user); returningNothing() }.execute()
				enumTestTable.insert { values(enum); returningNothing() }.execute()
				usersTable.select {
					val enumTest = join(enumTestTable)
					where {
						userId equalTo enumTest.enumTestId
					}
					returning(`*`, enumTest.`*`)
				}.single() shouldBe Result2(user, enum)
			}
		}

		"and/or subexpressions should work" {
			sql {
				usersTable.insert {
					values(userName = "Bob")
					returningNothing()
				}.execute()
				usersTable.select {
					where {
						anyOf {
							userName equalTo parameter("Joe")
							userName equalTo parameter("Bob")
						}
					}
					returning(userName)
				}.single() shouldBe Result1("Bob")
				usersTable.select {
					where {
						allOf {
							userName equalTo parameter("Joe")
							userName equalTo parameter("Bob")
						}
					}
					returning(userName)
				}.list() shouldBe listOf()
			}
		}
		"basic addition" {
			sql {
				usersTable.select {
					where {
						userId equalTo parameter(1L) + parameter(1L)
					}
					returningNothing()
				}.execute()
			}
		}
		"nullability" {
			sql {
				usersTable.insert {
					values(userName = "Bob")
					returningNothing()
				}.execute()

				usersTable.select {
					where {
						emailAddress.isNull()
					}
					returning(`*`)
				}.singleOrNull() shouldNotBe null

				usersTable.select {
					where {
						emailAddress.isNotNull()
					}
					returning(`*`)
				}.singleOrNull() shouldBe null
			}
		}
	}
}

class SubqueryTest : DatabaseTest() {
	init {
		"should be able to do a select/update lock query" {
			sql {
				usersTable.insert {
					values(userName = "Bob")
					returningNothing()
				}.execute()
				usersTable.update {
					userId setTo parameter(3L)
					where {
						userName equalTo uniqueSubquery(usersTable.select {
							where {
								userId equalTo parameter(1L)
							}
							forUpdate()
							skipLocked()
							limit(1)
							returning(userName)
						})
					}
					returning(`*`)
				}.execute()
			}
		}
	}
}

class UpsertTest : DatabaseTest() {
	init {
		"should be able to do a basic upsert" {
			sql {
				val user = UsersRow(1, "test", "foo@bar.com", addressId = null)
				usersTable.insert { values(user); returningNothing() }.execute()
				val user2 = user.copy(emailAddress = "baz@bing.com")
				usersTable.insert {
					values(user2)
					onConflictDoUpdate(userId) { excluded ->
						emailAddress setTo excluded.emailAddress
					}
					returningNothing()
				}.execute()

				usersTable.select {
					returning(`*`)
				}.value() shouldBe user2

				//Where clause limits insert
				usersTable.insert {
					values(user)
					onConflictDoUpdate(userId) { excluded ->
						emailAddress setTo excluded.emailAddress
						where {
							emailAddress notEqualTo parameter(user2.emailAddress)
						}
					}
					returningNothing()
				}.execute()

				//Should still be user2
				usersTable.select {
					returning(`*`)
				}.value() shouldBe user2
			}
		}
	}
}


class EnumTest : DatabaseTest() {
	init {
		"should be able to handle enums" {
			sql {
				enumTestTable.select {
					returning(enumTest)
				}
				enumTestTable.run {
					insert {
						values(enumTest = EnumTypeTest.test2)
						returningNothing()
					}.execute()

					select { returning(`*`) }.value().enumTest shouldBe EnumTypeTest.test2
				}
			}
		}
	}
}

class ArrayTest : DatabaseTest() {
	init {
		"should be able to handle array columns" {
			sql {
				val testList = listOf("a", "b", "c")
				arrayTestTable.run {
					insert {
						values(arrayColumn = testList)
						returningNothing()
					}.execute()
					select { returning(`*`) }.value().arrayColumn shouldBe testList
					select { returning(arrayColumn) }.value() shouldBe testList
				}
			}
		}
	}
}

class CommonTypeTest : DatabaseTest() {
	init {
		"should be able to handle UUIDs and timestamps" {
			sql {
				val uuid = CommonUUID(100L, 200L)
				val timestamp = CommonTimestamp(2000, 3000)
				commonTestTable.insert {
					values(uuid = uuid, timestamp = timestamp)
					returningNothing()
				}.execute()
				val returned = commonTestTable.select { returning(`*`) }.value()
				returned.uuid shouldBe uuid
				returned.timestamp shouldBe timestamp
			}
		}
	}
}

fun docTest(){
	val DATABASE_URL = "test"

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

	sql {

		usersTable.update {
			userName setTo parameter("Joe Smith")
			where {
				userName equalTo parameter("test")
			}
			returning(userId)
		}.values()
	}

	sql {

		usersTable.delete {
			where {
				userName equalTo parameter("test")
			}
			returningNothing()
		}.execute()
	}

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
}