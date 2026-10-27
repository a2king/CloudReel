package com.cloudreel.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeShelfTest {
    private val classes = """
        {
          "class": [
            {"type_id": 1, "type_pid": 0, "type_name": "电影"},
            {"type_id": 6, "type_pid": 1, "type_name": "动作片"},
            {"type_id": 2, "type_pid": 0, "type_name": "电视剧"},
            {"type_id": 13, "type_pid": 2, "type_name": "国产剧"},
            {"type_id": 3, "type_pid": 0, "type_name": "综艺"},
            {"type_id": 4, "type_pid": 0, "type_name": "动漫"}
          ]
        }
    """.trimIndent()

    @Test
    fun picksParentCategoryInsteadOfChild() {
        val types = parseCmsClasses(classes)
        assertEquals("1", cmsTypeId(types, CmsKind.MOVIE))
        assertEquals("2", cmsTypeId(types, CmsKind.SERIES))
        assertEquals("3", cmsTypeId(types, CmsKind.VARIETY))
        assertEquals(listOf("6"), cmsShelfTypeIds(types, CmsKind.MOVIE))
        assertEquals(listOf("13"), cmsShelfTypeIds(types, CmsKind.SERIES))
        assertEquals(listOf("3"), cmsShelfTypeIds(types, CmsKind.VARIETY))
        assertEquals(CmsKind.SERIES, homeShelfKind(HomeShelf.HOT_DRAMA))
        assertEquals(mapOf("ac" to "detail", "pg" to "1", "t" to "2", "h" to "72"), cmsShelfQuery("2", "72"))
    }

    @Test
    fun usesChildCategoriesWhenTheParentGroupIsEmpty() {
        val types = parseCmsClasses(
            """
            {"class":[
              {"type_id":1,"type_pid":0,"type_name":"电影片"},
              {"type_id":6,"type_pid":1,"type_name":"动作片"},
              {"type_id":11,"type_pid":1,"type_name":"剧情片"},
              {"type_id":2,"type_pid":0,"type_name":"连续剧"},
              {"type_id":13,"type_pid":2,"type_name":"国产剧"},
              {"type_id":16,"type_pid":2,"type_name":"欧美剧"},
              {"type_id":3,"type_pid":0,"type_name":"综艺片"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("6", "11"), cmsShelfTypeIds(types, CmsKind.MOVIE))
        assertEquals(listOf("13", "16"), cmsShelfTypeIds(types, CmsKind.SERIES))
        assertEquals(listOf("3"), cmsShelfTypeIds(types, CmsKind.VARIETY))
    }

    @Test
    fun ignoresAnimeWhenMatchingSeries() {
        val types = parseCmsClasses("""{"class":[{"type_id":"4","type_pid":"0","type_name":"动漫"}]}""")
        assertNull(cmsTypeId(types, CmsKind.SERIES))
    }

    @Test
    fun ranksByHitsThenScore() {
        data class Item(val name: String, val hits: Long, val score: Double)
        val ranked = listOf(
            Item("低", 1, 9.0),
            Item("热", 20, 1.0),
            Item("同分高分", 20, 8.0),
        ).sortedByCmsRank({ it.hits }, { it.score })
        assertEquals(listOf("同分高分", "热", "低"), ranked.map { it.name })
    }
}