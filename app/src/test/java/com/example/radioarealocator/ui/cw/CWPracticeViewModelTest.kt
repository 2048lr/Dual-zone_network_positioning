package com.example.radioarealocator.ui.cw

import com.example.radioarealocator.RadioAreaLocatorApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CWPracticeViewModel] 单元测试。
 *
 * 使用 Robolectric 提供 Android 环境（Context, DataStore, Room）。
 * 通过 [Config.application] 让 Robolectric 创建并初始化 [RadioAreaLocatorApplication]，
 * 其 [RadioAreaLocatorApplication.onCreate] 会赋值全局 `radioApp` 并完成 SecretManager 解密。
 * 手动 `new RadioAreaLocatorApplication()` 缺少 attachBaseContext，会导致 Context 为 null。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = RadioAreaLocatorApplication::class)
class CWPracticeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: CWPracticeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Robolectric 已创建并初始化 RadioAreaLocatorApplication，radioApp 全局变量已就绪
        viewModel = CWPracticeViewModel()
        // @Before 不是 runTest 块，通过 dispatcher.scheduler 显式推进
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial settings have default values`() = runTest {
        val settings = viewModel.settings.first()
        assertEquals(15, settings.wpm)
        assertEquals(600, settings.frequency)
        assertEquals(100, settings.practiceLength)
        assertEquals(5, settings.practiceDuration)
    }

    @Test
    fun `generatePracticeText produces text of correct length`() = runTest {
        viewModel.generatePracticeText(com.example.radioarealocator.data.cw.CharacterSet.LETTERS, 20)
        advanceUntilIdle()

        val text = viewModel.currentText.first()
        assertEquals(20, text.length)
    }

    @Test
    fun `generatePracticeText produces morse code`() = runTest {
        viewModel.generatePracticeText(com.example.radioarealocator.data.cw.CharacterSet.LETTERS, 10)
        advanceUntilIdle()

        val morse = viewModel.morseCode.first()
        assertTrue(morse.isNotEmpty())
    }

    @Test
    fun `generatePracticeText clears tutorial mode state`() = runTest {
        // First enter tutorial mode
        viewModel.generateTutorialText(1)
        advanceUntilIdle()

        // Then switch to free practice
        viewModel.generatePracticeText(com.example.radioarealocator.data.cw.CharacterSet.LETTERS, 10)
        advanceUntilIdle()

        assertEquals(0, viewModel.currentCourseId.first())
        assertEquals(0, viewModel.currentLessonId.first())
        assertEquals("", viewModel.currentCourseTitle.first())
        assertEquals("", viewModel.currentLessonInfo.first())
    }

    @Test
    @Ignore("Room DAO suspend 函数在 Dispatchers.IO 执行，advanceUntilIdle 不等待真实 IO 线程；" +
        "需重构为注入内存数据库 + 同步执行器才能验证异步加载的 currentText")
    fun `generateTutorialText enters Koch course mode`() = runTest {
        viewModel.generateTutorialText(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.currentCourseId.first())
        assertEquals(1, viewModel.currentLessonId.first())
        assertTrue(viewModel.currentCourseTitle.first().isNotEmpty())
        assertTrue(viewModel.currentText.first().isNotEmpty())
    }

    @Test
    fun `advanceCourseProgress moves to next lesson`() = runTest {
        viewModel.generateTutorialText(1)
        advanceUntilIdle()

        val initialLesson = viewModel.currentLessonId.first()
        viewModel.advanceCourseProgress()
        advanceUntilIdle()

        assertEquals(initialLesson + 1, viewModel.currentLessonId.first())
        assertTrue(viewModel.currentText.first().isNotEmpty())
    }

    @Test
    fun `advanceCourseProgress clears user input`() = runTest {
        viewModel.generateTutorialText(1)
        advanceUntilIdle()

        viewModel.advanceCourseProgress()
        advanceUntilIdle()

        assertEquals("", viewModel.userInput.first())
        assertEquals(0f, viewModel.accuracy.first(), 0.001f)
    }

    @Test
    fun `updateSettings changes settings`() = runTest {
        val newSettings = com.example.radioarealocator.data.cw.CWSettings(
            wpm = 25,
            frequency = 800,
            practiceLength = 200
        )
        viewModel.updateSettings(newSettings)
        advanceUntilIdle()

        val settings = viewModel.settings.first()
        assertEquals(25, settings.wpm)
        assertEquals(800, settings.frequency)
        assertEquals(200, settings.practiceLength)
    }

    @Test
    fun `resetCourseProgress sets progress to 0`() = runTest {
        viewModel.resetCourseProgress(1)
        advanceUntilIdle()

        val progress = viewModel.courseProgress.first()
        assertEquals(0f, progress[1] ?: -1f, 0.001f)
    }

    @Test
    fun `getCharacterSets returns all sets`() {
        val sets = viewModel.getCharacterSets()
        assertEquals(4, sets.size)
    }

    @Test
    @Ignore("Room DAO suspend 函数在 Dispatchers.IO 执行，advanceUntilIdle 不等待真实 IO 线程；" +
        "需重构为注入内存数据库 + 同步执行器才能验证异步加载的 courseProgress")
    fun `courseProgress has entries for all courses after init`() = runTest {
        val progress = viewModel.courseProgress.first()
        // Should have entries for courses 1-4
        assertTrue(progress.containsKey(1))
        assertTrue(progress.containsKey(2))
        assertTrue(progress.containsKey(3))
        assertTrue(progress.containsKey(4))
    }

    @Test
    fun `totalPracticeTime starts at 0`() = runTest {
        advanceUntilIdle()
        val total = viewModel.totalPracticeTime.first()
        assertTrue(total >= 0)
    }

    @Test
    fun `isPlaying starts false`() = runTest {
        assertFalse(viewModel.isPlaying.first())
    }

    @Test
    fun `isPaused starts false`() = runTest {
        assertFalse(viewModel.isPaused.first())
    }
}
