package me.foxtails.oldmanvideo

import org.junit.Test

import org.junit.Assert.assertEquals

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun rootEntries_flattensRootChildren() {
        val roots = listOf(listOf("movie", "trailer"), listOf("episode"))

        assertEquals(listOf("movie", "trailer", "episode"), flattenRootEntries(roots))
    }

    @Test
    fun rootEntries_preservesNestedFolders() {
        val nestedFolder = "season-folder"

        assertEquals(listOf(nestedFolder), flattenRootEntries(listOf(listOf(nestedFolder))))
    }
}