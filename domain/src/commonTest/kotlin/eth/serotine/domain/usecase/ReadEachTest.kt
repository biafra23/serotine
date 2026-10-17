package eth.serotine.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReadEachTest {

    private val sources = mutableMapOf<String, MutableStateFlow<Int>>()
    private val started = mutableListOf<String>()

    private fun read(key: String) = sources.getOrPut(key) { MutableStateFlow(0) }.also { started += key }

    @Test
    fun eachKeyIsReadOnceAndAKeyGoneTakesItsReadingWithIt() = runTest {
        val keys = MutableStateFlow(setOf("a", "b"))
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch { keys.readEach(keys = { it }, read = ::read).collect { seen += it.second } }
        runCurrent()
        sources.getValue("a").value = 1
        runCurrent()
        assertEquals(mapOf("a" to 1, "b" to 0), seen.last())

        keys.value = setOf("a")
        runCurrent()
        assertEquals(mapOf("a" to 1), seen.last(), "b's reading leaves with it")

        keys.value = setOf("a", "b")
        sources.getValue("b").value = 7
        runCurrent()
        assertEquals(mapOf("a" to 1, "b" to 7), seen.last())
        assertEquals(listOf("a", "b", "b"), started, "a is read once throughout; b again when it is named again")
    }

    @Test
    fun anUpstreamThatEndsKeepsItsReadersGoing() = runTest {
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch {
            flowOf(setOf("a")).readEach(keys = { it }, read = ::read).map { it.second }.collect { seen += it }
        }
        runCurrent()
        sources.getValue("a").value = 3
        runCurrent()

        assertEquals(mapOf("a" to 3), seen.last())
    }
}
