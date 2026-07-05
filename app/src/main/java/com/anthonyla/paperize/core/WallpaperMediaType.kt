package com.anthonyla.paperize.core

/**
 * Media type for wallpapers
 *
 * Determines what type of media the wallpaper file is, which affects
 * how it's decoded before being applied as wallpaper.
 */
enum class WallpaperMediaType {
    /**
     * Image files: JPEG, PNG, WEBP, AVIF, HEIC, HEIF, BMP, GIF, TIFF
     * Supported in both static and live wallpaper modes
     */
    IMAGE,

    /**
     * Video files. Static wallpaper mode uses a representative frame.
     * Full-motion live wallpaper playback is not implemented yet.
     */
    VIDEO;

    /**
     * Supported file extensions for this media type
     */
    val supportedExtensions: Set<String>
        get() = when (this) {
            IMAGE -> setOf(
                "jpg", "jpeg", "png", "webp", "avif",
                "heic", "heif", // HEIC/HEIF - Apple's high efficiency format
                "bmp",          // Bitmap - legacy but still used
                "gif",          // GIF - mostly for static images (first frame used)
                "tiff", "tif",  // TIFF - high quality archival format
                "svg"           // SVG - vector graphics (rasterized for wallpaper)
            )
            VIDEO -> setOf(
                "mp4", "m4v", "3gp", "webm", "mkv"
            )
        }

    /**
     * Whether this media type is supported in static wallpaper mode
     */
    val supportedInStaticMode: Boolean
        get() = this == IMAGE || this == VIDEO

    /**
     * Whether this media type is supported in live wallpaper mode
     */
    val supportedInLiveMode: Boolean
        get() = this == IMAGE || this == VIDEO

    companion object {
        /**
         * Detect media type from file extension
         */
        fun fromExtension(extension: String): WallpaperMediaType? {
            val ext = extension.lowercase()
            return entries.find { ext in it.supportedExtensions }
        }

        /**
         * Convert string to WallpaperMediaType
         */
        fun fromString(value: String?): WallpaperMediaType? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
