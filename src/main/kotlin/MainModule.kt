import net.justmachinery.kdbgen.kapt.QueryContainer
import net.justmachinery.kdbgen.kapt.SqlGenerationSettings
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.sql.EnumTypeTest

private const val TEST_DATABASE_URL : String = "jdbc:pgsql://localhost:5432/kdbgentest?user=kdbgentest&password=kdbgentest"


@SqlGenerationSettings(
    databaseUrl = TEST_DATABASE_URL
)
@QueryContainer
class MainModule {
    @SqlQuery("useAnEnum",
        "SELECT * FROM enum_test"
    )
    fun test(){}

    val testProp = EnumTypeTest.test2
}