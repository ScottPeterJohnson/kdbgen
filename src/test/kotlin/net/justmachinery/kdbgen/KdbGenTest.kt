package net.justmachinery.kdbgen

import MainModule
import com.impossibl.postgres.api.data.Range
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.justmachinery.kdbgen.kapt.QueryContainer
import net.justmachinery.kdbgen.kapt.SqlPrelude
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.sql.composites.InventoryItem
import net.justmachinery.kdbgen.sql.enums.EnumTypeTest
import net.justmachinery.kdbgen.testcustom.CustomClass
import java.util.*

@SqlPrelude("create temporary view prelude_test as select 1")
@QueryContainer
class AnnotationQueriesTest : DatabaseTest(), AnnotationQueriesTestQueries {
	@SqlQuery("addition",
		"SELECT :addendum::bigint + :addendum AS foobar"
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
	@SqlQuery("maxNullable",
		"select max(address_id) from addresses"
	)
	@SqlQuery("maxNonNullable",
		"select coalesce(max(address_id), 0) from addresses",
		columnCanBeNull = [false]
	)
	fun otherQueries(){}
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
	@SqlQuery("aUserByAnyOtherName",
		"""SELECT u1.user_name as name1, u2.user_name as name2 FROM users u1 join users u2 using (email_address) WHERE u1.user_name = :name?"""
	)
	@SqlQuery("nullableArray",
		"select '{foo, bar, NULL}'::text[]"
	)
	@SqlQuery("arrayParameter",
		"select :array::text[]"
	)
	@SqlQuery("xmlTest",
		"select * from xml_test"
	)
	@SqlQuery("xmlInsert",
		"insert into xml_test (uuid, text) values (:uuid, :text)"
	)
	@SqlQuery("enumTestInsert", /* language=PostgreSQL */ """INSERT INTO enum_test (enum_test) VALUES (:enumTestValue)""")
	@SqlQuery("enumTestSelect", "SELECT * FROM enum_test")
	fun queries(){}

	init {
		"basic tests" - {
			"should be able to do basic operations" {
				sql {
					addition(3).first() shouldBe 6
					insertUser("foobar")
					selectAllUsers().first().user_name shouldBe "foobar"
					nullableSelect(null)
					namedSelect("foobar") shouldBe otherNamedSelect()
					customClassSelect().first() shouldBe CustomClass("foobar")
					deleteUser("foobar")
					selectAllUsers() shouldBe emptyList()
					foobaz(3)
					casting().first() shouldBe "13"
				}
			}
			"max() should be nullable" {
				sql {
					maxNullable().single() shouldBe null
				}
			}
			"unnullable max() should be unnullable" {
				sql {
					val long: Long = maxNonNullable().single()
					long shouldBe 0
				}
			}
			"basic structs" {
				sql {
					val item = InventoryItem("foo", 0, 3.5)
					structInsert(item, 5)
					val otherItem = structTest().map { it.item }.single()
					item.name shouldBe otherItem?.name
					item.supplier_id shouldBe otherItem?.supplier_id
					item.price?.toDouble() shouldBe (otherItem?.price?.toDouble()?.plusOrMinus(0.01))

				}
			}
			"ranges should parse" {
				sql {
					rangeInsert(Range.create(null, true, null, true))
					rangeTest()
				}
			}
		}
		"arrays" - {
			"should be able to select arrays" {
				sql {
					nullableArray().first()?.first() shouldBe "foo"
					arrayParameter(listOf("test", "test", "test"))
					System.gc() //PGBuffersArray might log an error if not freed properly
				}
			}
		}
		"xml" - {
			"behold xml operations" {
				sql {
					val xml = "<foo>test</foo>"
					xmlInsert(UUID.randomUUID(), connection.createSQLXML().apply { string = xml })
					xmlTest().map { it.text.string }.single() shouldBe xml
				}
			}
		}
		"enums" - {
			"enums should work" {
				sql {
					MainModule().testProp shouldBe EnumTypeTest.test2
					enumTestInsert(EnumTypeTest.test2)
					enumTestSelect().first().enum_test shouldBe EnumTypeTest.test2
				}
			}
		}
		"batching" - {
			"basic batching" {
				sql {
					insertUser.batch {
						add("1")
						add("2")
						add("3")
					}
					selectAllUsers().map { it.user_name } shouldBe listOf("1", "2", "3")
				}
			}
		}
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