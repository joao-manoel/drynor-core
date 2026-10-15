package rs.drynor.core.persistence

import com.google.gson.reflect.TypeToken
import org.darkan.core.JsonFileManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class JsonFileTypesTest {
    @Test fun `parameterized files retain integer map keys and primitive arrays`() {
        val file = Files.createTempFile("drynor-json-types-", ".json").toFile()
        try {
            file.writeText("""{"4112":[231510698,-1,0,2147483647]}""")
            val type = TypeToken.getParameterized(Map::class.java, Int::class.javaObjectType, IntArray::class.java).type
            val loaded: Map<Int, IntArray>? = JsonFileManager.loadJsonFile(file, type)
            assertContentEquals(intArrayOf(231510698, -1, 0, Int.MAX_VALUE), assertNotNull(loaded)[4112])
        } finally {
            file.delete()
        }
    }
}
