package com.yingshicang.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AppHttpUrlTest {
    @Test
    fun convertsInternationalHost() {
        assertEquals(
            "http://www.xn--sss604efuw.cc/tv",
            toAsciiHttpUrl("http://www.饭太硬.cc/tv"),
        )
        assertEquals(
            "https://example.com/a.json",
            toAsciiHttpUrl("https://example.com/a.json"),
        )
    }
}
