package com.smalljelly.batterydata.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 机型库匹配：边界、变体、代号前缀、冲突消歧。 */
@RunWith(AndroidJUnit4::class)
class DesignCapacityMatchingTest {

    private val entries by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = context.assets.open("battery_models.json").bufferedReader().use { it.readText() }
        DesignCapacityResolver.debugParse(json)
    }

    private fun match(vararg identifiers: String): Float? =
        DesignCapacityResolver.debugBestMatch(entries, identifiers.toList())?.entry?.capacity

    @Test
    fun exactMarketingNamesKeepWorking() {
        assertEquals(6100f, match("Xiaomi 15 Pro") ?: -1f, 0.01f)
        assertEquals(5240f, match("Xiaomi 15") ?: -1f, 0.01f)
        assertEquals(6100f, match("2410DPNACC") ?: -1f, 0.01f)
    }

    @Test
    fun normalizationHandlesCaseUnderscoreAndSpaces() {
        assertEquals(6100f, match("  XIAOMI_15_PRO  ") ?: -1f, 0.01f)
        assertEquals(6100f, match("Xiaomi  15   Pro") ?: -1f, 0.01f)
    }

    @Test
    fun plusVariantsMapToSameEntry() {
        assertEquals(6200f, match("Redmi Note 14 Pro+") ?: -1f, 0.01f)
        assertEquals(6200f, match("Redmi Note 14 Pro +") ?: -1f, 0.01f)
        assertEquals(6200f, match("Redmi Note 14 Pro Plus") ?: -1f, 0.01f)
    }

    @Test
    fun unnamedVariantMustNotBorrowBaseModel() {
        assertNull(match("Xiaomi 15T Pro"))
        assertNull(match("Pixel 9 Pro Fold"))
        assertNull(match("Xiaomi 15 Ultra Pro"))
    }

    @Test
    fun hardwareCodePrefixMatchesRegionalSuffix() {
        assertEquals(5000f, match("SM-S9280") ?: -1f, 0.01f)
    }

    @Test
    fun conflictingIdentifiersResolveToUnknown() {
        // Mate 60 = 4750，Pixel 9 = 4700，同级同长且容量冲突
        assertNull(match("Mate 60", "Pixel 9"))
    }

    @Test
    fun invalidEntriesAreDroppedWithoutLosingValidOnes() {
        val parsed = DesignCapacityResolver.debugParse(
            """
            [
              {"keys": ["a very long invalid model key"], "capacity": 99999},
              {"keys": ["valid model"], "capacity": 5000}
            ]
            """.trimIndent(),
        )
        assertEquals(1, parsed.size)
        assertEquals(5000f, DesignCapacityResolver.debugBestMatch(parsed, listOf("valid model"))?.entry?.capacity ?: -1f, 0.01f)
    }
}
