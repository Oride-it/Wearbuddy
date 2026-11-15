package com.wear.wearbuddy.data

import org.junit.Assert.*
import org.junit.Test

class ArtifactKeysTest {

    @Test fun `sandbox artifacts retain workspace and decode names once`() {
        assertEquals("/workspace/中文 报告.md", ArtifactKeys.workspacePath("agent:///workspace/%E4%B8%AD%E6%96%87%20%E6%8A%A5%E5%91%8A.md"))
        assertEquals("/workspace/report.pdf", ArtifactKeys.workspacePath("agent://files/workspace/report.pdf"))
        assertEquals("/workspace/report.pdf", ArtifactKeys.workspacePath("file:///workspace/report.pdf"))
        assertEquals("/workspace/a+b.txt", ArtifactKeys.workspacePath("agent:///workspace/a+b.txt"))
    }

    @Test fun `sandbox reads reject traversal including double encoded traversal`() {
        for (uri in listOf("agent:///root/a", "agent:///workspace/../secret", "agent:///workspace/%2e%2e/secret",
            "agent:///workspace/%252e%252e/secret", "agent:///workspace/a%5Cb", "agent:///workspace/a%00b")) {
            assertNull(uri, ArtifactKeys.workspacePath(uri))
        }
    }

    @Test fun `encoded Chinese name yields a decoded variant`() {
        val variants = ArtifactKeys.variantsOf(
            "agent://files/%E4%B8%AD%E6%96%87%E6%8A%A5%E5%91%8A%202026.md"
        )
        assertEquals("中文报告 2026.md", variants[1])
        assertTrue(variants.contains("中文报告 2026.md"))
    }

    @Test fun `plain names produce a single raw variant`() {
        assertEquals(listOf("report.txt"), ArtifactKeys.variantsOf("agent://files/report.txt"))
    }

    @Test fun `encoded and raw keys both appear when they differ`() {
        val variants = ArtifactKeys.variantsOf("agent://files/a%20b.txt")
        assertEquals(listOf("a%20b.txt", "a b.txt"), variants.take(2))
        assertEquals(2, variants.distinct().size)
    }

    @Test fun `trailing punctuation from chat text is trimmed`() {
        assertEquals("报告.md", ArtifactKeys.trimTail("报告.md。"))
        assertEquals("report.txt", ArtifactKeys.trimTail("report.txt."))
        assertEquals("notes.md", ArtifactKeys.trimTail("notes.md，"))
        assertEquals("v1.0", ArtifactKeys.trimTail("v1.0"))
        assertEquals("a.txt", ArtifactKeys.variantsOf("agent://files/a.txt。").single())
    }

    @Test fun `redundant leading slashes and query fragments are removed`() {
        assertEquals(listOf("uploads/a.txt"), ArtifactKeys.variantsOf("agent://files//uploads/a.txt"))
        assertEquals(listOf("a.txt"), ArtifactKeys.variantsOf("agent://files/a.txt?token=1#frag"))
        assertEquals(listOf("a.txt"), ArtifactKeys.variantsOf("agent://files/a.txt"))
    }

    @Test fun `literal plus is preserved and never decoded to space`() {
        assertEquals("a+b.txt", ArtifactKeys.decodeSegment("a+b.txt"))
        val variants = ArtifactKeys.variantsOf("agent://files/a+b.txt")
        assertEquals("a+b.txt", variants.first())
        assertTrue(variants.none { ' ' in it })
        assertTrue("a%2Bb.txt" in variants)
    }

    @Test fun `invalid percent escapes stay untouched`() {
        assertEquals("100%ZZ.txt", ArtifactKeys.decodeSegment("100%ZZ.txt"))
        assertEquals("%4", ArtifactKeys.decodeSegment("%4"))
    }

    @Test fun `multi byte percent runs decode as UTF-8`() {
        assertEquals("中文", ArtifactKeys.decodeSegment("%E4%B8%AD%E6%96%87"))
        assertEquals("中x文", ArtifactKeys.decodeSegment("%E4%B8%ADx%E6%96%87"))
        assertEquals("📄.md", ArtifactKeys.decodeSegment("%F0%9F%93%84.md"))
        assertEquals("📄x ", ArtifactKeys.decodeSegment("📄x%20"))
    }

    @Test fun `non file uris are rejected`() {
        assertTrue(ArtifactKeys.variantsOf("https://example.com/a.txt").isEmpty())
        assertTrue(ArtifactKeys.variantsOf("http://example.com/a.txt").isEmpty())
        assertTrue(ArtifactKeys.variantsOf("agent://files/").isEmpty())
        assertTrue(ArtifactKeys.variantsOf("   ").isEmpty())
    }

    @Test fun `encode segment matches encodeURIComponent for reserved characters`() {
        assertEquals("a%20b.txt", ArtifactKeys.encodeSegment("a b.txt"))
        assertEquals("%E4%B8%AD%E6%96%87.md", ArtifactKeys.encodeSegment("中文.md"))
        assertEquals("a%2Bb%26c%3Dd%3F", ArtifactKeys.encodeSegment("a+b&c=d?"))
        assertEquals("-_.!~*'()", ArtifactKeys.encodeSegment("-_.!~*'()"))
        assertEquals("a%2Fb", ArtifactKeys.encodeSegment("a/b"))
    }

    @Test fun `encoded variants survive an encode decode round trip`() {
        val original = "uploads/2026/中文 报告.md"
        val encoded = ArtifactKeys.encodeSegment("中文 报告.md")
        assertEquals("uploads/2026/$encoded", original.split('/').joinToString("/") { ArtifactKeys.encodeSegment(it) })
        assertEquals(original, ArtifactKeys.variantsOf("agent://files/uploads/2026/$encoded").getOrNull(1))
    }
}
