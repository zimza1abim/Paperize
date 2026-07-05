package com.anthonyla.paperize.domain.model

data class LockOverlayGuide(
    val screenshotUri: String? = null,
    val alpha: Float = DEFAULT_ALPHA
) {
    fun sanitized(): LockOverlayGuide = copy(
        alpha = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
    )

    companion object {
        const val MIN_ALPHA = 0f
        const val MAX_ALPHA = 0.9f
        const val DEFAULT_ALPHA = 0.45f

        val Default = LockOverlayGuide()
    }
}
