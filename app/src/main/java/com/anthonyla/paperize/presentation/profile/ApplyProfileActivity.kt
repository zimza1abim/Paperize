package com.anthonyla.paperize.presentation.profile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.anthonyla.paperize.domain.usecase.ApplyWallpaperProfileUseCase
import com.anthonyla.paperize.domain.usecase.ProfileApplyResult
import com.anthonyla.paperize.domain.usecase.SaveWallpaperProfileUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
open class ApplyProfileActivity : ComponentActivity() {

    protected open val fixedProfileAction: String? = null
    protected open val fixedProfileId: Int? = null

    @Inject
    lateinit var applyProfileUseCase: ApplyWallpaperProfileUseCase

    @Inject
    lateinit var saveProfileUseCase: SaveWallpaperProfileUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val command = resolveCommand()
            if (command == null) {
                Toast.makeText(this@ApplyProfileActivity, "Invalid profile action", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            when (command.action) {
                ProfileAction.SAVE -> handleSave(command.profileId)
                ProfileAction.APPLY -> handleApply(command.profileId)
            }
            finish()
        }
    }

    private fun resolveCommand(): ProfileCommand? {
        val fixedAction = fixedProfileAction?.toProfileAction()
        val fixedId = fixedProfileId
        if (fixedAction != null && fixedId in 1..3) {
            return ProfileCommand(fixedAction, fixedId!!)
        }

        val extras = intent?.extras
        val extraProfileId = when (val value = extras?.get("profile_id")) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        val extraAction = when (extras?.get("profile_action")?.toString()) {
            "save" -> ProfileAction.SAVE
            "apply" -> ProfileAction.APPLY
            else -> null
        }
        if (extraAction != null && extraProfileId in 1..3) {
            return ProfileCommand(extraAction, extraProfileId!!)
        }

        val uri = intent?.data
        val uriProfileId = uri?.getQueryParameter("id")?.toIntOrNull()
        val uriAction = when (uri?.path) {
            "/save" -> ProfileAction.SAVE
            "/apply" -> ProfileAction.APPLY
            else -> null
        }
        if (uriAction != null && uriProfileId in 1..3) {
            return ProfileCommand(uriAction, uriProfileId!!)
        }

        val componentName = intent?.component?.className.orEmpty()
        return when {
            componentName.endsWith(".SaveProfile1Activity") -> ProfileCommand(ProfileAction.SAVE, 1)
            componentName.endsWith(".SaveProfile2Activity") -> ProfileCommand(ProfileAction.SAVE, 2)
            componentName.endsWith(".SaveProfile3Activity") -> ProfileCommand(ProfileAction.SAVE, 3)
            componentName.endsWith(".ApplyProfile1Activity") -> ProfileCommand(ProfileAction.APPLY, 1)
            componentName.endsWith(".ApplyProfile2Activity") -> ProfileCommand(ProfileAction.APPLY, 2)
            componentName.endsWith(".ApplyProfile3Activity") -> ProfileCommand(ProfileAction.APPLY, 3)
            else -> null
        }
    }

    private fun String.toProfileAction(): ProfileAction? = when (this) {
        "save" -> ProfileAction.SAVE
        "apply" -> ProfileAction.APPLY
        else -> null
    }

    private suspend fun handleSave(profileId: Int) {
        when (saveProfileUseCase(profileId)) {
            ProfileApplyResult.Applied -> Toast.makeText(this, "Profile $profileId saved", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, "Failed to save profile $profileId", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun handleApply(profileId: Int) {
        when (applyProfileUseCase(profileId)) {
            ProfileApplyResult.Applied -> Toast.makeText(this, "Profile $profileId applied", Toast.LENGTH_SHORT).show()
            ProfileApplyResult.NeedsLiveWallpaperSelection -> {
                Toast.makeText(this, "Select Paperize live wallpaper", Toast.LENGTH_SHORT).show()
                startActivity(applyProfileUseCase.liveWallpaperSelectionIntent())
            }
            ProfileApplyResult.NotFound -> Toast.makeText(this, "Profile $profileId is not saved", Toast.LENGTH_SHORT).show()
            ProfileApplyResult.InvalidProfile -> Toast.makeText(this, "Profile $profileId is incomplete", Toast.LENGTH_SHORT).show()
            ProfileApplyResult.DeferredUntilUnlocked -> Toast.makeText(this, "Unlock the phone before applying profile", Toast.LENGTH_SHORT).show()
            ProfileApplyResult.Failed -> Toast.makeText(this, "Failed to apply profile $profileId", Toast.LENGTH_SHORT).show()
        }
    }
}

private data class ProfileCommand(
    val action: ProfileAction,
    val profileId: Int
)

private enum class ProfileAction {
    SAVE,
    APPLY
}


