package courier.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {

    @Test
    fun testVersionParsing() {
        val v1 = SemanticVersion.parse("1.5.0")
        assertNotNull(v1)
        assertEquals(1, v1.major)
        assertEquals(5, v1.minor)
        assertEquals(0, v1.patch)
        assertNull(v1.preRelease)

        val v2 = SemanticVersion.parse("v1.5.2-beta1")
        assertNotNull(v2)
        assertEquals(1, v2.major)
        assertEquals(5, v2.minor)
        assertEquals(2, v2.patch)
        assertEquals("beta1", v2.preRelease)

        val v3 = SemanticVersion.parse("2.0")
        assertNotNull(v3)
        assertEquals(2, v3.major)
        assertEquals(0, v3.minor)
        assertEquals(0, v3.patch)

        val invalid = SemanticVersion.parse("not-a-version")
        assertNull(invalid)
    }

    @Test
    fun testVersionComparison() {
        val v140 = SemanticVersion.parse("1.4.0")!!
        val v150 = SemanticVersion.parse("1.5.0")!!
        val v151 = SemanticVersion.parse("1.5.1")!!
        val v160 = SemanticVersion.parse("1.6.0")!!
        val v200 = SemanticVersion.parse("2.0.0")!!

        assertTrue(v150 > v140)
        assertTrue(v151 > v150)
        assertTrue(v160 > v151)
        assertTrue(v200 > v160)

        // Equal versions
        val v150WithV = SemanticVersion.parse("v1.5.0")!!
        assertEquals(0, v150.compareTo(v150WithV))

        // Prerelease comparison
        val v150Rc = SemanticVersion.parse("1.5.0-rc1")!!
        assertTrue(v150 > v150Rc)
    }
}