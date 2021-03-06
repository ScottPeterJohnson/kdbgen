package net.justmachinery.kdbgen

import com.impossibl.postgres.api.jdbc.PGConnection
import io.kotlintest.AbstractSpec
import io.kotlintest.TestType
import io.kotlintest.specs.IntelliMarker
import net.justmachinery.kdbgen.kapt.SqlGenerationSettings
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private const val TEST_DATABASE_URL : String = "jdbc:pgsql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest"


@SqlGenerationSettings(
	databaseUrl = TEST_DATABASE_URL
)
abstract class DatabaseTest : AbstractSpec(), IntelliMarker {
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