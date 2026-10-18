package org.drynor.core.deploy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtifactWatcherTest {
    private val metadata = """
        <metadata>
          <versioning>
            <latest>3.0.0-master-2300000010</latest>
            <versions>
              <version>3.0.0-dev-2299999990</version>
              <version>3.0.0-dev-2300000005</version>
              <version>3.0.0-master-2300000010</version>
              <version>3.0.0-dev-2300000001</version>
            </versions>
          </versioning>
        </metadata>
    """.trimIndent()

    @Test
    fun `newest build on the channel wins by pipeline id, not registry order or latest`() {
        assertEquals("3.0.0-dev-2300000005", ArtifactWatcher.newestOnChannel(metadata, "dev"))
        assertEquals("3.0.0-master-2300000010", ArtifactWatcher.newestOnChannel(metadata, "master"))
    }

    @Test
    fun `no build on the channel means nothing to update to`() {
        assertNull(ArtifactWatcher.newestOnChannel(metadata, "feature-x"))
        assertNull(ArtifactWatcher.newestOnChannel("", "dev"))
    }

    @Test
    fun `release channel takes the newest release tag`() {
        val releases = """[{"name":"3.1.0","tag_name":"3.1.0","assets":{"links":[]}},{"name":"3.0.0","tag_name":"3.0.0"}]"""
        assertEquals("3.1.0", ArtifactWatcher.newestReleaseTag(releases))
        assertNull(ArtifactWatcher.newestReleaseTag("[]"))
    }
}
