package net.justmachinery.kdbgen

import io.jscry.DatabaseTest
import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.should
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldNot
import net.justmachinery.kdbgen.test.generated.enums.EnumTypeTest
import net.justmachinery.kdbgen.test.generated.tables.*

class BasicOperationsTest : DatabaseTest() {
	init {
		"should be able to do basic operations" {
			into(usersTable).insert { values { it.userName("Bob") } }.execute(connection)
			from(usersTable).selectAll().where { it.userName equalTo "Bob" }.execute(connection) shouldNot beEmpty()
			from(usersTable).update { it.userName setTo "Joe" }.returning(usersTable.userName).execute(connection).first().first shouldBe "Joe"
			val (deletedEmailAddress) = from(usersTable).delete().returning(usersTable.emailAddress).execute(connection).first()
			deletedEmailAddress shouldBe null
			from(usersTable).selectAll().execute(connection) should beEmpty()
			into(usersTable).insert { values({ it.userName("Bob") }, { it.userName("Joe") }) }.execute(connection)
		}
	}
}

class EnumTest : DatabaseTest() {
	init {
		"should be able to handle enums" {
			into(enumTestTable).insert { values { it.enumTest(EnumTypeTest.test2) } }.execute(connection)
			from(enumTestTable).selectAll().execute(connection).first().enumTest shouldBe EnumTypeTest.test2
		}
	}
}

class ArrayTest : DatabaseTest() {
	init {
		"should be able to handle array columns" {
			val testList = listOf("a", "b", "c")
			into(arrayTestTable).insert { values { it.arrayColumn(testList) }}.execute(connection)
			from(arrayTestTable).selectAll().execute(connection).first().arrayColumn shouldBe testList
			from(arrayTestTable).select(arrayTestTable.arrayColumn).execute(connection).first().first shouldBe testList
		}
	}
}