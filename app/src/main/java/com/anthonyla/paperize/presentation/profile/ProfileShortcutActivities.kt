package com.anthonyla.paperize.presentation.profile

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SaveProfile1Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "save"
    override val fixedProfileId: Int = 1
}

@AndroidEntryPoint
class SaveProfile2Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "save"
    override val fixedProfileId: Int = 2
}

@AndroidEntryPoint
class SaveProfile3Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "save"
    override val fixedProfileId: Int = 3
}

@AndroidEntryPoint
class ApplyProfile1Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "apply"
    override val fixedProfileId: Int = 1
}

@AndroidEntryPoint
class ApplyProfile2Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "apply"
    override val fixedProfileId: Int = 2
}

@AndroidEntryPoint
class ApplyProfile3Activity : ApplyProfileActivity() {
    override val fixedProfileAction: String = "apply"
    override val fixedProfileId: Int = 3
}


