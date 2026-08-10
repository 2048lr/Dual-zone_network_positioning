package com.example.hamkit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeImageStoreTest {

    @Test
    fun `sample size accounts for either oversized dimension`() {
        val companion = LandscapeImageStore::class.java.declaredFields
            .first { it.name == "Companion" }
            .apply { isAccessible = true }
            .get(null)
        val method = companion.javaClass.declaredMethods
            .first { it.name == "calculateInSampleSize" }
            .apply { isAccessible = true }

        val result = method.invoke(companion, 4000, 1000, 1920, 1080) as Int

        assertEquals(2, result)
    }
}
