package io.github.realtvop.pixelcardemulator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun laterPatchMinorOrMajorIsNewer() {
        assertTrue(isNewerVersion("0.1.2", "0.1.1"))
        assertTrue(isNewerVersion("0.2", "0.1.9"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test
    fun sameOrEarlierIsNotNewer() {
        assertFalse(isNewerVersion("0.1.1", "0.1.1"))
        assertFalse(isNewerVersion("0.1", "0.1.1"))
        assertFalse(isNewerVersion("0.1.0", "0.2"))
    }

    @Test
    fun partsCompareAsNumbers() {
        assertTrue(isNewerVersion("0.10", "0.9"))
        assertFalse(isNewerVersion("0.9", "0.10"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertFalse(isNewerVersion("0.1.0", "0.1"))
        assertFalse(isNewerVersion("0.1", "0.1.0"))
        assertTrue(isNewerVersion("0.1.0.1", "0.1"))
    }

    @Test
    fun tagPrefixIsIgnored() {
        assertTrue(isNewerVersion("v0.1.2", "0.1.1"))
        assertFalse(isNewerVersion("v0.1.1", "0.1.1"))
        assertTrue(isNewerVersion("V1.0", "v0.9"))
    }

    @Test
    fun preReleaseComesBeforeItsRelease() {
        assertTrue(isNewerVersion("0.2.0", "0.2.0-beta1"))
        assertFalse(isNewerVersion("0.2.0-beta1", "0.2.0"))
        assertTrue(isNewerVersion("0.2.1-beta1", "0.2.0"))
        assertFalse(isNewerVersion("0.2.0-beta2", "0.2.0-beta1"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertFalse(isNewerVersion("0.1.1+build.7", "0.1.1"))
        assertFalse(isNewerVersion("0.1.1+build-7", "0.1.1"))
    }

    @Test
    fun unparseableVersionsAreNeverNewer() {
        assertFalse(isNewerVersion("latest", "0.1.1"))
        assertFalse(isNewerVersion("", "0.1.1"))
        assertFalse(isNewerVersion("0..2", "0.1.1"))
        assertFalse(isNewerVersion("0.2", "dev"))
        assertFalse(isNewerVersion("99999999999.0", "0.1.1"))
    }
}
