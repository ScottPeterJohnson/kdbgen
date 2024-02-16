import net.justmachinery.kdbgen.kapt.QueryContainer
import net.justmachinery.kdbgen.kapt.SqlGenerationSettings
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.sql.enums.EnumTypeTest


@QueryContainer
class MainModule {
    @SqlQuery("useAnEnum",
        "SELECT * FROM enum_test"
    )
    fun test(){}

    val testProp = EnumTypeTest.test2
}