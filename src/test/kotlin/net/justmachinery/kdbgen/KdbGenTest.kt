package net.justmachinery.kdbgen

import io.kotlintest.matchers.beEmpty
import io.kotlintest.should
import io.kotlintest.shouldBe
import net.justmachinery.kdbgen.kapt.QueryContainer
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.sql.AnnotationQueriesTestQueries
import net.justmachinery.kdbgen.sql.EnumTypeTest
import net.justmachinery.kdbgen.testcustom.CustomClass
import net.justmachinery.kdbgen.testcustom.CustomResultSet


@QueryContainer
class AnnotationQueriesTest : DatabaseTest(), AnnotationQueriesTestQueries {
	@SqlQuery("addition",
		"SELECT 1 + :addendum AS foobar"
	)
	@SqlQuery("insertUser",
		"""INSERT INTO users(user_name) VALUES (:name) RETURNING *"""
	)
	@SqlQuery("selectAllUsers", "SELECT * FROM users")
	@SqlQuery("deleteUser",
		"""DELETE FROM users WHERE user_name = :name"""
	)
	@SqlQuery("nullableSelect",
		"""SELECT * FROM users WHERE user_name = :name?"""
	)
	@SqlQuery("namedSelect",
		"""SELECT * FROM users WHERE user_name = :name?""",
		"UserResult"
	)
	@SqlQuery("otherNamedSelect",
		"""SELECT * FROM users""",
		"UserResult"
	)
	@SqlQuery("customClassSelect",
		"""SELECT user_name FROM users """,
		"net.justmachinery.kdbgen.testcustom.CustomClass"
	)
	@SqlQuery("foobaz",
		"SELECT 1 + :addendum AS foobar"
	)
	@SqlQuery("casting",
		"SELECT '1' || 3::text AS foobar"
	)
	fun test(){
		"should be able to do basic operations" {
			sql {
				addition(3).first() shouldBe 4
				insertUser("foobar")
				selectAllUsers().first().user_name shouldBe "foobar"
				nullableSelect(null)
				namedSelect("foobar") shouldBe otherNamedSelect()
				customClassSelect().first() shouldBe CustomClass("foobar")
				deleteUser("foobar")
				selectAllUsers() should beEmpty()
				foobaz(3)
				casting().first() shouldBe "13"
			}
		}
	}

	@SqlQuery("enumTestInsert", /* language=PostgreSQL */ """INSERT INTO enum_test (enum_test) VALUES (:enumTestValue)""")
	@SqlQuery("enumTestSelect", "SELECT * FROM enum_test")
	fun enums(){
		"enums should work" {
			sql {
				enumTestInsert(EnumTypeTest.test2)
				enumTestSelect().first().enum_test shouldBe EnumTypeTest.test2
			}
		}
	}

	@SqlQuery("multiResultSets", "SELECT * FROM enum_test; SELECT * FROM users")
	@SqlQuery("namedMultiResultSets", "SELECT * FROM enum_test; SELECT * FROM users", "NamedMultiResultSet")
	@SqlQuery("customResultSets", "SELECT * FROM unnest('{1,2}'::bigint[]); SELECT user_name FROM users", "net.justmachinery.kdbgen.testcustom.CustomResultSet")
	fun multipleResultSets(){
        "multi result sets should work"{
            sql {
                enumTestInsert(EnumTypeTest.test2)
                insertUser("foobar")
                multiResultSets()
                namedMultiResultSets()
                customResultSets() shouldBe CustomResultSet(listOf(1,2), listOf("foobar"))
            }
        }
	}

	init {
		test()
		enums()
        multipleResultSets()
	}
}
