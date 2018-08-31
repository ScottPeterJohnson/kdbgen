package net.justmachinery.kdbgen

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.should
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldNot
import net.justmachinery.kdbgen.dsl.clauses.deleteReturning
import net.justmachinery.kdbgen.dsl.clauses.insert
import net.justmachinery.kdbgen.dsl.clauses.select
import net.justmachinery.kdbgen.dsl.clauses.updateReturning
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
					}.execute()
					select(`*`) {
						where {
							userName equalTo "Bob"
						}
					}.values() shouldNot beEmpty()
					updateReturning {
						userName setTo "Joe"
						returning(userName)
					}.value() shouldBe "Joe"

					val deletedEmailAddress = deleteReturning {
						returning(emailAddress)
					}.value()
					deletedEmailAddress shouldBe null
					select(`*`) {}.values() should beEmpty()
					val users = listOf("Bob", "Frank", "Joe")
					insert {
						for(user in users){
							values(userName = user)
						}
					}.execute()

					select(userName){
						where {
							userName within users
						}
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
				enumTestTable.run {
					insert {
						values(enumTest = EnumTypeTest.test2)
					}.execute()

					select(`*`) {}.value().enumTest shouldBe EnumTypeTest.test2
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
					}.execute()
					select(`*`) {}.value().arrayColumn shouldBe testList
					select(arrayColumn) {}.value() shouldBe testList
				}
			}
		}
	}
}
