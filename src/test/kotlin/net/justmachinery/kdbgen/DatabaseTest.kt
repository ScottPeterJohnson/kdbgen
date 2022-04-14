package net.justmachinery.kdbgen

import com.impossibl.postgres.api.jdbc.PGConnection
import io.kotest.core.spec.style.FreeSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import net.justmachinery.kdbgen.kapt.SqlGenerationSettings
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private const val TEST_DATABASE_URL : String = "jdbc:pgsql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest"


@SqlGenerationSettings(
	databaseUrl = TEST_DATABASE_URL
)
abstract class DatabaseTest : FreeSpec() {
	val connection = DriverManager.getConnection(TEST_DATABASE_URL, Properties()) as PGConnection
	init {
		connection.autoCommit = false
        connection.createStatement().use {
            it.execute(ClassLoader.getSystemClassLoader().getResource("prelude.sql")!!.readText())
        }
	}
	val connectionProvider = object : ConnectionProvider {
		override fun getConnection(): Connection {
			return connection
		}
	}

	override suspend fun afterAny(testCase: TestCase, result: TestResult) {
		connection.rollback()
	}

	fun sql(cb : ConnectionProvider.()->Unit){
		cb(connectionProvider)
	}
}