package com.smalljelly.batterydata.data

import android.util.Xml
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemDesignCapacityReaderTest {

    @Test
    fun readsOnlyDesignCapacityFromSystemProfile() {
        val capacity = read(
            """
            <device>
                <item name="cpu.idle">3.5</item>
                <array name="cpu.active"><value>120</value></array>
                <item name="battery.capacity"> 5240 </item>
            </device>
            """.trimIndent(),
        )
        assertEquals(5240f, capacity!!, 0f)
    }

    @Test
    fun missingCapacityDoesNotInferFromOtherValues() {
        assertNull(read("<device><item name=\"battery.full\">4800</item></device>"))
    }

    @Test
    fun rejectsDefaultInvalidAndNonFiniteCapacities() {
        listOf("1000", "0", "-5000", "NaN", "Infinity", "5000000", "unknown", "").forEach { value ->
            assertNull("Unexpected capacity: $value", read(
                "<device><item name=\"battery.capacity\">$value</item></device>",
            ))
        }
    }

    private fun read(xml: String): Float? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        return SystemDesignCapacityReader.parse(parser)
    }
}
