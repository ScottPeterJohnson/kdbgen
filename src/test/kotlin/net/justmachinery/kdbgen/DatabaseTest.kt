package net.justmachinery.kdbgen

import io.kotlintest.AbstractSpec
import io.kotlintest.TestType
import io.kotlintest.specs.IntelliMarker
import net.justmachinery.kdbgen.dsl.ConnectionProvider
import net.justmachinery.kdbgen.kapt.GeneratePostgresInterface
import net.justmachinery.kdbgen.kapt.SqlGenerationSettings
import org.postgresql.jdbc.PgConnection
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private const val TEST_DATABASE_URL : String = "jdbc:postgresql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest"

@GeneratePostgresInterface
@SqlGenerationSettings(
	databaseUrl = TEST_DATABASE_URL,
	enumPackage = "net.justmachinery.kdbgen.test.generated.enums",
	dataPackage = "net.justmachinery.kdbgen.test.generated.tables",
	useCommonTypes = true,
	mutableData = true
)
abstract class DatabaseTest : AbstractSpec(), IntelliMarker {
	val connection = DriverManager.getConnection(TEST_DATABASE_URL, Properties()) as PgConnection
	private val dummyConnection = object : Connection by connection {
		override fun close() {}
	}
	init {
		connection.autoCommit = false
	}
	val connectionProvider = object : ConnectionProvider {
		override fun getConnection(): Connection {
			return dummyConnection
		}
	}
	fun sql(cb : ConnectionProvider.()->Unit){
		cb(connectionProvider)
	}

	infix operator fun String.invoke(run: () -> Unit) {
		addTestCase(this, {
			try { run() }
			finally {
				connection.rollback()
			}
		}, defaultTestCaseConfig, TestType.Test)
	}
}