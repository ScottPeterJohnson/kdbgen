package net.justmachinery.kdbgen

import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestSuite
import net.justmachinery.kdbgen.dsl.ConnectionProvider
import org.postgresql.jdbc.PgConnection
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private val TEST_DATABASE_URL : String = "jdbc:postgresql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest"

abstract class DatabaseTest : Spec() {
	val connection = DriverManager.getConnection(TEST_DATABASE_URL, Properties()) as PgConnection
	init {
		connection.autoCommit = false
	}
	val connectionProvider = object : ConnectionProvider {
		override fun getConnection(): Connection {
			return connection
		}
	}
	fun sql(cb : ConnectionProvider.()->Unit){
		cb(connectionProvider)
	}

	private var current = rootTestSuite

	private var testCaseInitializer: (()->Unit)? = null
	fun suite(suiteName : String, init : ()->Unit, cases : ()->Unit){
		val suite = TestSuite(suiteName)
		current.addNestedSuite(suite)
		val temp = current
		current = suite
		testCaseInitializer = init
		cases()
		testCaseInitializer = null
		current = temp
	}

	infix operator fun String.invoke(run: () -> Unit): TestCase {
		val initializer = testCaseInitializer?:{}
		val tc = TestCase(
				suite = current,
				name = this,
				test = { initializer(); run(); connection.rollback() },
				config = defaultTestCaseConfig)
		current.addTestCase(tc)
		return tc
	}
}