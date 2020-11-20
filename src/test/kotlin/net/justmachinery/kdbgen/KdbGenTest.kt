package net.justmachinery.kdbgen

import com.impossibl.postgres.api.data.Range
import io.kotlintest.matchers.beEmpty
import io.kotlintest.should
import io.kotlintest.shouldBe
import net.justmachinery.kdbgen.kapt.QueryContainer
import net.justmachinery.kdbgen.kapt.SqlPrelude
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.sql.AnnotationQueriesTestQueries
import net.justmachinery.kdbgen.sql.EnumTypeTest
import net.justmachinery.kdbgen.sql.InventoryItem
import net.justmachinery.kdbgen.testcustom.CustomClass
import net.justmachinery.kdbgen.testcustom.CustomResultSet

@SqlPrelude("create temporary view prelude_test as select 1")
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
    @SqlQuery("prelude",
        "select * from prelude_test"
    )
    @SqlQuery("nullableArray",
        "select '{foo, bar, NULL}'::text[]"
    )
	@SqlQuery("maxNullable",
		"select max(address_id) from addresses"
	)
	@SqlQuery("xmlTest",
		"select * from xml_test"
	)
	@SqlQuery("rangeInsert",
		"insert into range_test (interval) values (:range)"
	)
	@SqlQuery("rangeTest",
		"select * from range_test"
	)
	@SqlQuery("structInsert",
		"insert into on_hand (item, count) values (:item, :count)"
	)
	@SqlQuery("structTest",
		"select * from on_hand"
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
                nullableArray().first()?.first() shouldBe "foo"
			}
		}
		"max() should be nullable" {
			sql {
				maxNullable().single() shouldBe null
			}
		}
        "can we even do structs" {
            sql {
				val item = InventoryItem("foo", 0, 3.5)
				structInsert(item, 5)
                val otherItem = structTest().map { it.item }.single()
				item.name shouldBe otherItem?.name
				item.supplier_id shouldBe otherItem?.supplier_id
				item.price?.toDouble() shouldBe otherItem?.price?.toDouble()

            }
        }
		"behold xml operations" {
			sql {
				xmlTest()
			}
		}
		"ranges should parse" {
			sql {
				rangeInsert(Range.create(null, true, null, true))
				rangeTest()
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

	/*@SqlQuery("multiResultSets", "SELECT * FROM enum_test; SELECT * FROM users")
	@SqlQuery("namedMultiResultSets", "SELECT * FROM enum_test; SELECT * FROM users", "NamedMultiResultSet")
	@SqlQuery("customResultSets", "SELECT * FROM unnest('{1,2}'::bigint[]); SELECT user_name FROM users", "net.justmachinery.kdbgen.testcustom.CustomResultSet")
	@SqlQuery("nonResultSets", "SELECT * FROM enum_test; UPDATE enum_test SET enum_test_id = 0 WHERE false; DELETE FROM enum_test WHERE false; SELECT user_name FROM users   ")
	fun multipleResultSets(){
        "multi result sets should work"{
            sql {
                enumTestInsert(EnumTypeTest.test2)
                insertUser("foobar")
                multiResultSets()
                namedMultiResultSets()
                customResultSets() shouldBe CustomResultSet(listOf(1,2), listOf("foobar"))
				nonResultSets()
            }
        }
	}*/

	init {
		test()
		enums()
        //multipleResultSets()
	}
}

@SqlPrelude("""
    create temporary view prelude_test_1 as select 1
""")
class PreludeTest1
@SqlPrelude("""
    create temporary view prelude_test_2 as select * from prelude_test_3
""", dependencies = [ PreludeTest3::class ])
@SqlPrelude("""
    create temporary view prelude_test_2_b as select true
""")
class PreludeTest2
@SqlPrelude("""
    create temporary view prelude_test_3 as select * from prelude_test_1
""", dependencies = [ PreludeTest1::class ])
class PreludeTest3