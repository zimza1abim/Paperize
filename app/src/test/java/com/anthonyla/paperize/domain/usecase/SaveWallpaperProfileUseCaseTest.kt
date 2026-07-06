package com.anthonyla.paperize.domain.usecase

import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.WallpaperProfileSnapshot
import com.anthonyla.paperize.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveWallpaperProfileUseCaseTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val useCase = SaveWallpaperProfileUseCase(settingsRepository)

    @Test
    fun `invoke rejects profile ids below supported range`() = runTest {
        val result = useCase(0)

        assertEquals(ProfileApplyResult.InvalidProfile, result)
        coVerify(exactly = 0) { settingsRepository.saveWallpaperProfile(any()) }
    }

    @Test
    fun `invoke rejects profile ids above supported range`() = runTest {
        val result = useCase(4)

        assertEquals(ProfileApplyResult.InvalidProfile, result)
        coVerify(exactly = 0) { settingsRepository.saveWallpaperProfile(any()) }
    }

    @Test
    fun `invoke saves each supported profile id`() = runTest {
        coEvery { settingsRepository.getWallpaperMode() } returns WallpaperMode.STATIC
        coEvery { settingsRepository.getScheduleSettings() } returns ScheduleSettings(
            homeAlbumId = "home",
            lockAlbumId = "lock"
        )
        coEvery { settingsRepository.getWallpaperProfile(any()) } returns null

        (1..3).forEach { id ->
            val result = useCase(id)

            assertEquals(ProfileApplyResult.Applied, result)
            coVerify { settingsRepository.saveWallpaperProfile(match<WallpaperProfileSnapshot> { it.id == id }) }
        }
    }
}
