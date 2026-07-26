package com.example.radioarealocator.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [launchSearchQueryCollector] 单元测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSearchQueryCollectorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `collects debounced query`() = runTest(testDispatcher) {
        val queryFlow = MutableStateFlow("")
        val results = mutableListOf<String>()

        val job = launchSearchQueryCollector(queryFlow) { query ->
            results.add(query)
        }

        // Emit multiple values rapidly - only the last should pass debounce
        queryFlow.value = "a"
        queryFlow.value = "ab"
        queryFlow.value = "abc"

        advanceTimeBy(200) // Past debounce threshold (150ms)
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertEquals("abc", results[0])
        // collectLatest 是 long-running 收集器，需显式 cancel 否则 runTest 报 UncompletedCoroutinesError
        job.cancel()
    }

    @Test
    fun `does not emit duplicate consecutive values`() = runTest(testDispatcher) {
        val queryFlow = MutableStateFlow("")
        val results = mutableListOf<String>()

        val job = launchSearchQueryCollector(queryFlow) { query ->
            results.add(query)
        }

        queryFlow.value = "test"
        advanceTimeBy(200)
        advanceUntilIdle()

        queryFlow.value = "test" // Same value
        advanceTimeBy(200)
        advanceUntilIdle()

        // Only one emission because distinctUntilChanged
        assertEquals(1, results.size)
        job.cancel()
    }

    @Test
    fun `emits after debounce period`() = runTest(testDispatcher) {
        val queryFlow = MutableStateFlow("")
        val results = mutableListOf<String>()

        val job = launchSearchQueryCollector(queryFlow) { query ->
            results.add(query)
        }

        queryFlow.value = "hello"
        advanceTimeBy(100) // Not past debounce (150ms)
        // 不调用 advanceUntilIdle()：它会继续推进虚拟时间到 debounce 触发点，
        // 导致 "Too early" 断言失败。advanceTimeBy 已执行完 100ms 内就绪的任务。

        assertEquals(0, results.size) // Too early

        advanceTimeBy(50) // Now past 150ms total
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertEquals("hello", results[0])
        job.cancel()
    }

    @Test
    fun `empty query is collected`() = runTest(testDispatcher) {
        val queryFlow = MutableStateFlow("initial")
        val results = mutableListOf<String>()

        val job = launchSearchQueryCollector(queryFlow) { query ->
            results.add(query)
        }

        queryFlow.value = ""
        advanceTimeBy(200)
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertEquals("", results[0])
        job.cancel()
    }
}
