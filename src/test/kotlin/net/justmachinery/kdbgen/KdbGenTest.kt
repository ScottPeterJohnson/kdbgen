package net.justmachinery.kdbgen

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.should
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldNot
import net.justmachinery.kdbgen.test.generated.enums.EnumTypeTest
import net.justmachinery.kdbgen.test.generated.tables.arrayTestTable
import net.justmachinery.kdbgen.test.generated.tables.enumTestTable
import net.justmachinery.kdbgen.test.generated.tables.usersTable

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
