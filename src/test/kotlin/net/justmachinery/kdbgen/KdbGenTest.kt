package net.justmachinery.kdbgen

import io.kotlintest.matchers.beEmpty
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import net.justmachinery.kdbgen.common.CommonTimestamp
import net.justmachinery.kdbgen.common.CommonUUID
import net.justmachinery.kdbgen.dsl.clauses.Result2
import net.justmachinery.kdbgen.dsl.clauses.join
import net.justmachinery.kdbgen.test.generated.enums.EnumTypeTest
import net.justmachinery.kdbgen.test.generated.tables.*


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
							userName equalTo "Bob"
						}
						returning(`*`)
					}.values() shouldNot beEmpty()
					update {
						userName setTo "Joe"
						returning(userName)
					}.value() shouldBe "Joe"

					val deletedEmailAddress = delete {
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
				val user = UsersRow(1, null, "foo@bar.com")
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
	}
}

class UpsertTest : DatabaseTest() {
	init {
		"should be able to do a basic upsert" {
			sql {
				val user = UsersRow(1, null, "foo@bar.com")
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
