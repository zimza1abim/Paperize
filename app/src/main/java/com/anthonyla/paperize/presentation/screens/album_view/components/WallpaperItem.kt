package com.anthonyla.paperize.presentation.screens.album_view.components
import com.anthonyla.paperize.core.constants.Constants

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMediaType
import com.anthonyla.paperize.presentation.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperItem(
    wallpaperUri: String,
    mediaType: WallpaperMediaType = WallpaperMediaType.IMAGE,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoThumbnail by produceState<Bitmap?>(
        initialValue = null,
        key1 = wallpaperUri,
        key2 = mediaType
    ) {
        value = if (mediaType == WallpaperMediaType.VIDEO) {
            withContext(Dispatchers.IO) {
                loadVideoThumbnail(
                    context = context,
                    uriString = wallpaperUri,
                    width = Constants.GRID_THUMBNAIL_WIDTH,
                    height = Constants.GRID_THUMBNAIL_HEIGHT
                )
            }
        } else {
            null
        }
    }

    // Animate selection state changes
    val transition = updateTransition(isSelected, label = "WallpaperItemSelection")
    val paddingTransition by transition.animateDp(label = "padding") { selected ->
        if (selected) 5.dp else 0.dp
    }
    val roundedCornerShapeTransition by transition.animateDp(label = "roundedCornerShape") { selected ->
        if (selected) 24.dp else 16.dp
    }

    Card(
        modifier = modifier
            .padding(paddingTransition)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(roundedCornerShapeTransition),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mediaType == WallpaperMediaType.VIDEO && videoThumbnail != null) {
                Image(
                    bitmap = videoThumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = if (isSelected) 0.7f else 1f
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(wallpaperUri.toUri())
                        .size(Size(Constants.GRID_THUMBNAIL_WIDTH, Constants.GRID_THUMBNAIL_HEIGHT))  // Limit size for performance - suitable for grid thumbnails
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = if (isSelected) 0.7f else 1f
                )
            }

            if (mediaType == WallpaperMediaType.VIDEO) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(AppSpacing.small)
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Selection indicator
            if (isSelectionMode) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.content_desc_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppSpacing.small)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = stringResource(R.string.content_desc_not_selected),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppSpacing.small)
                    )
                }
            }
        }
    }
}

private fun loadVideoThumbnail(
    context: android.content.Context,
    uriString: String,
    width: Int,
    height: Int
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uriString.toUri())
        retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            width,
            height
        ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}
