package com.anthonyla.paperize.presentation.screens.wallpaper_view

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.WallpaperMediaType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.LockOverlayGuideManager
import com.anthonyla.paperize.core.util.LockOverlayPresetManager
import com.anthonyla.paperize.domain.model.LockOverlayGuide
import com.anthonyla.paperize.domain.model.LockOverlayPreset
import com.anthonyla.paperize.domain.model.WallpaperFraming
import com.anthonyla.paperize.presentation.theme.AppSpacing
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperViewScreen(
    wallpaperUri: String,
    wallpaperName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WallpaperViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val zoomState = rememberZoomState()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaType = remember(wallpaperUri, wallpaperName) {
        val nameExtension = wallpaperName.substringAfterLast('.', missingDelimiterValue = "")
        val uriExtension = wallpaperUri.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "")
        WallpaperMediaType.fromExtension(nameExtension)
            ?: WallpaperMediaType.fromExtension(uriExtension)
    }
    val isVideo = mediaType == WallpaperMediaType.VIDEO
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isEditingFraming by remember { mutableStateOf(false) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var overlayPreset by remember { mutableStateOf(LockOverlayPresetManager.load(context)) }
    var overlayGuide by remember { mutableStateOf(LockOverlayGuideManager.load(context)) }
    var draftFraming by remember { mutableStateOf(uiState.framing) }
    var draftClockX by remember(overlayPreset) { mutableFloatStateOf(overlayPreset.clockX) }
    var draftClockY by remember(overlayPreset) { mutableFloatStateOf(overlayPreset.clockY) }
    var draftClockScale by remember(overlayPreset) { mutableFloatStateOf(overlayPreset.clockScale) }
    var previewTarget by remember { mutableStateOf(FramingPreviewTarget.HOME) }
    val previewScalingType = remember(
        previewTarget,
        uiState.wallpaperMode,
        uiState.homeScalingType,
        uiState.lockScalingType,
        uiState.liveScalingType
    ) {
        when (uiState.wallpaperMode) {
            WallpaperMode.LIVE -> uiState.liveScalingType
            else -> when (previewTarget) {
                FramingPreviewTarget.LOCK -> uiState.lockScalingType
                else -> uiState.homeScalingType
            }
        }
    }
    val guidePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val nextGuide = overlayGuide.copy(screenshotUri = uri.toString()).sanitized()
        overlayGuide = nextGuide
        LockOverlayGuideManager.save(context, nextGuide)
    }

    LaunchedEffect(wallpaperUri) {
        viewModel.loadWallpaper(wallpaperUri)
    }

    LaunchedEffect(uiState.framing, isEditingFraming) {
        if (!isEditingFraming) {
            draftFraming = uiState.framing
        }
    }

    LaunchedEffect(uiState.wallpaperMode) {
        if (uiState.wallpaperMode == WallpaperMode.LIVE) {
            previewTarget = FramingPreviewTarget.LIVE
        } else if (previewTarget == FramingPreviewTarget.LIVE) {
            previewTarget = FramingPreviewTarget.HOME
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    if (zoomState.scale <= 1.01f || isEditingFraming) {
                        Text(
                            text = if (isEditingFraming) "Adjust framing" else wallpaperName,
                            color = MaterialTheme.colorScheme.surfaceBright,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .padding(AppSpacing.large)
                            .requiredSize(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.surfaceBright
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!isEditingFraming) {
                BottomAppBar(
                    containerColor = Color.Transparent,
                    actions = {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = AppSpacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    draftFraming = uiState.framing
                                    isEditingFraming = true
                                },
                                enabled = uiState.wallpaper != null
                            ) {
                                Text("Framing")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(if (isEditingFraming) PaddingValues(0.dp) else padding)
                    .then(if (isVideo || isEditingFraming) Modifier else Modifier.zoomable(zoomState))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { contentSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .framingPreview(
                                framing = if (isEditingFraming) draftFraming else WallpaperFraming.Default,
                                contentSize = contentSize,
                                enabled = isEditingFraming,
                                onTransform = { pan, zoom ->
                                    draftFraming = draftFraming.transformBy(
                                        pan = pan,
                                        zoom = zoom,
                                        viewportSize = contentSize
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVideo) {
                            AndroidView(
                                factory = { context ->
                                    VideoView(context).apply {
                                        videoView = this
                                        setVideoURI(wallpaperUri.toUri())
                                        setOnPreparedListener { player ->
                                            player.isLooping = true
                                            disableVideoAudio(player)
                                            start()
                                        }
                                    }
                                },
                                update = { view ->
                                    if (view.tag != wallpaperUri) {
                                        view.tag = wallpaperUri
                                        view.setVideoURI(wallpaperUri.toUri())
                                        view.setOnPreparedListener { player ->
                                            player.isLooping = true
                                            disableVideoAudio(player)
                                            view.start()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            AsyncImage(
                                model = wallpaperUri.toUri(),
                                contentDescription = wallpaperName,
                                contentScale = if (isEditingFraming) {
                                    previewScalingType.toContentScale()
                                } else {
                                    ContentScale.Fit
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    if (isEditingFraming) {
                        LockScreenFrameGuide(
                            framing = draftFraming,
                            overlayGuide = overlayGuide,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (overlayGuide.screenshotUri == null) {
                            LockScreenPreviewOverlay(
                                preset = overlayPreset.copy(
                                    clockX = draftClockX,
                                    clockY = draftClockY,
                                    clockScale = draftClockScale
                                ).sanitized(),
                                editable = true,
                                onClockDrag = { drag ->
                                    val width = contentSize.width.coerceAtLeast(1)
                                    val height = contentSize.height.coerceAtLeast(1)
                                    draftClockX = (draftClockX + drag.x / width)
                                        .coerceIn(LockOverlayPreset.MIN_POSITION, LockOverlayPreset.MAX_POSITION)
                                    draftClockY = (draftClockY + drag.y / height)
                                        .coerceIn(LockOverlayPreset.MIN_POSITION, LockOverlayPreset.MAX_POSITION)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        OverlayCalibrationControls(
                            framing = draftFraming,
                            clockScale = draftClockScale,
                            onClockScaleChange = { draftClockScale = it },
                            overlayGuide = overlayGuide,
                            previewTarget = previewTarget,
                            previewScalingType = previewScalingType,
                            showPreviewTargetSelector = uiState.wallpaperMode != WallpaperMode.LIVE &&
                                uiState.homeScalingType != uiState.lockScalingType,
                            onPreviewTargetChange = { previewTarget = it },
                            onOverlayAlphaChange = {
                                overlayGuide = overlayGuide.copy(alpha = it).sanitized()
                                LockOverlayGuideManager.save(context, overlayGuide)
                            },
                            onPickOverlay = { guidePicker.launch(arrayOf("image/*")) },
                            onClearOverlay = {
                                overlayGuide = LockOverlayGuideManager.clearScreenshot(context)
                            },
                            onResetImage = {
                                draftFraming = WallpaperFraming.Default
                                viewModel.updateDraftFraming(WallpaperFraming.Default)
                            },
                            onResetClock = {
                                val resetPreset = LockOverlayPresetManager.reset(context)
                                overlayPreset = resetPreset
                            },
                            onCancel = {
                                viewModel.discardDraftFraming()
                                draftFraming = uiState.wallpaper?.framing?.sanitized() ?: WallpaperFraming.Default
                                draftClockX = overlayPreset.clockX
                                draftClockY = overlayPreset.clockY
                                draftClockScale = overlayPreset.clockScale
                                isEditingFraming = false
                            },
                            onSave = {
                                val savedPreset = overlayPreset.copy(
                                    clockX = draftClockX,
                                    clockY = draftClockY,
                                    clockScale = draftClockScale
                                ).sanitized()
                                LockOverlayPresetManager.save(context, savedPreset)
                                overlayPreset = savedPreset
                                viewModel.saveFraming(draftFraming)
                                isEditingFraming = false
                            },
                            saveEnabled = uiState.wallpaper != null && !uiState.isSaving,
                            isSaving = uiState.isSaving,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun disableVideoAudio(player: MediaPlayer) {
    player.setVolume(0f, 0f)
    runCatching {
        player.trackInfo.forEachIndexed { index, trackInfo ->
            if (trackInfo.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                player.deselectTrack(index)
            }
        }
    }
}

private enum class FramingPreviewTarget {
    HOME,
    LOCK,
    LIVE
}

private val FramingPreviewTarget.label: String
    get() = when (this) {
        FramingPreviewTarget.HOME -> "Home"
        FramingPreviewTarget.LOCK -> "Lock"
        FramingPreviewTarget.LIVE -> "Live"
    }

private fun ScalingType.toContentScale(): ContentScale {
    return when (this) {
        ScalingType.FILL -> ContentScale.Crop
        ScalingType.FIT -> ContentScale.Fit
        ScalingType.STRETCH -> ContentScale.FillBounds
        ScalingType.NONE -> ContentScale.None
    }
}

@Composable
private fun LockScreenPreviewOverlay(
    preset: LockOverlayPreset,
    editable: Boolean,
    onClockDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        LockStatusBarPreview(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp)
        )

        LockClockPreview(
            preset = preset,
            editable = editable,
            onClockDrag = onClockDrag,
            modifier = Modifier
                .fillMaxSize()
        )

        LockNotificationPreview(
            widgetY = preset.widgetY,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 28.dp, end = 28.dp)
        )

        Text(
            text = "Swipe to unlock",
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
        )
    }
}

@Composable
private fun LockStatusBarPreview(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "12:45",
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(10.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun LockClockPreview(
    preset: LockOverlayPreset,
    editable: Boolean,
    onClockDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = remember { LocalTime.now() }
    val today = remember { LocalDate.now() }
    val locale = Locale.KOREAN
    val timeText = remember(now) { "%02d : %02d".format(now.hour, now.minute) }
    val dateText = remember(today) {
        "${today.monthValue}월 ${today.dayOfMonth}일 ${
            today.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        }"
    }

    BoxWithConstraints(modifier = modifier) {
        val screenW = constraints.maxWidth
        val screenH = constraints.maxHeight
        val clockWidth = (280 * preset.clockScale).toInt()

        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (screenW * preset.clockX).toInt() - (clockWidth / 2),
                        y = (screenH * preset.clockY).toInt()
                    )
                }
                .then(
                    if (editable) {
                        Modifier.pointerInput(preset) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onClockDrag(dragAmount)
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateText,
                    fontSize = (15 * preset.clockScale).sp,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sunny",
                    fontSize = (15 * preset.clockScale).sp,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "28°C",
                    fontSize = (15 * preset.clockScale).sp,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = timeText,
                color = Color.White,
                fontSize = (76 * preset.clockScale).sp,
                lineHeight = (78 * preset.clockScale).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OverlayCalibrationControls(
    framing: WallpaperFraming,
    clockScale: Float,
    onClockScaleChange: (Float) -> Unit,
    overlayGuide: LockOverlayGuide,
    previewTarget: FramingPreviewTarget,
    previewScalingType: ScalingType,
    showPreviewTargetSelector: Boolean,
    onPreviewTargetChange: (FramingPreviewTarget) -> Unit,
    onOverlayAlphaChange: (Float) -> Unit,
    onPickOverlay: () -> Unit,
    onClearOverlay: () -> Unit,
    onResetImage: () -> Unit,
    onResetClock: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    isSaving: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Framing tools",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${previewTarget.label}: ${previewScalingType.name}  ·  Zoom ${(framing.scale * 100).toInt()}%  ·  X ${(framing.offsetX * 100).toInt()}  Y ${(framing.offsetY * 100).toInt()}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row {
                TextButton(onClick = onCancel, enabled = !isSaving) {
                    Text("Cancel")
                }
                TextButton(onClick = onSave, enabled = saveEnabled) {
                    Text(if (isSaving) "Saving" else "Save")
                }
            }
        }

        if (showPreviewTargetSelector) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FramingPreviewTargetButton(
                    text = "Home preview",
                    selected = previewTarget == FramingPreviewTarget.HOME,
                    onClick = { onPreviewTargetChange(FramingPreviewTarget.HOME) }
                )
                FramingPreviewTargetButton(
                    text = "Lock preview",
                    selected = previewTarget == FramingPreviewTarget.LOCK,
                    onClick = { onPreviewTargetChange(FramingPreviewTarget.LOCK) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = onResetImage, enabled = !isSaving) {
                Text("Reset image")
            }
            TextButton(onClick = onResetClock, enabled = !isSaving) {
                Text("Reset clock")
            }
            TextButton(onClick = onPickOverlay, enabled = !isSaving) {
                Text("Guide")
            }
            TextButton(
                onClick = onClearOverlay,
                enabled = overlayGuide.screenshotUri != null && !isSaving
            ) {
                Text("Clear")
            }
        }

        Text(
            text = "Clock preview size",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Slider(
            value = clockScale,
            onValueChange = onClockScaleChange,
            valueRange = LockOverlayPreset.MIN_CLOCK_SCALE..LockOverlayPreset.MAX_CLOCK_SCALE
        )

        if (overlayGuide.screenshotUri != null) {
            Text(
                text = "Screenshot guide opacity",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Slider(
                value = overlayGuide.alpha,
                onValueChange = onOverlayAlphaChange,
                valueRange = LockOverlayGuide.MIN_ALPHA..LockOverlayGuide.MAX_ALPHA
            )
        }

        Text(
            text = "Pinch or drag the wallpaper. The frame and guide are preview-only.",
            color = Color.White.copy(alpha = 0.76f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FramingPreviewTargetButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(
            color = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
        )
    ) {
        Text(
            text = text,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LockNotificationPreview(
    widgetY: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val screenH = constraints.maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(
                        x = 0,
                        y = (screenH * widgetY.coerceIn(0f, 1f)).toInt()
                    )
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(2) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.28f),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(28.dp)
                            .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(14.dp))
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (index == 0) 0.56f else 0.44f)
                                .height(8.dp)
                                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .fillMaxWidth(if (index == 0) 0.86f else 0.68f)
                                .height(7.dp)
                                .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.framingPreview(
    framing: WallpaperFraming,
    contentSize: IntSize,
    enabled: Boolean,
    onTransform: (pan: Offset, zoom: Float) -> Unit
): Modifier {
    if (!enabled) return this

    return this
        .graphicsLayer {
            scaleX = framing.scale
            scaleY = framing.scale
            translationX = framing.offsetX * size.width
            translationY = framing.offsetY * size.height
        }
        .pointerInput(enabled, contentSize) {
            detectTransformGestures { _, pan, zoom, _ ->
                onTransform(pan, zoom)
            }
        }
}

private fun WallpaperFraming.transformBy(
    pan: Offset,
    zoom: Float,
    viewportSize: IntSize
): WallpaperFraming {
    val width = viewportSize.width.coerceAtLeast(1)
    val height = viewportSize.height.coerceAtLeast(1)
    return WallpaperFraming(
        scale = (scale * zoom).coerceIn(WallpaperFraming.MIN_SCALE, WallpaperFraming.MAX_SCALE),
        offsetX = (offsetX + pan.x / width).coerceIn(WallpaperFraming.MIN_OFFSET, WallpaperFraming.MAX_OFFSET),
        offsetY = (offsetY + pan.y / height).coerceIn(WallpaperFraming.MIN_OFFSET, WallpaperFraming.MAX_OFFSET)
    )
}

@Composable
private fun LockScreenFrameGuide(
    framing: WallpaperFraming,
    overlayGuide: LockOverlayGuide,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val displayMetrics = LocalContext.current.resources.displayMetrics
        val screenAspect = remember(displayMetrics.widthPixels, displayMetrics.heightPixels) {
            displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.coerceAtLeast(1)
        }
        val viewportWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val frameRect = remember(viewportWidth, viewportHeight, screenAspect) {
            calculateCenteredAspectRect(viewportWidth, viewportHeight, screenAspect)
        }
        val imageRect = remember(framing, viewportWidth, viewportHeight) {
            calculateTransformedViewportRect(framing, viewportWidth, viewportHeight)
        }
        val coversFrame = imageRect.left <= frameRect.left &&
            imageRect.top <= frameRect.top &&
            imageRect.right >= frameRect.right &&
            imageRect.bottom >= frameRect.bottom
        val borderColor = if (coversFrame) {
            Color.White.copy(alpha = 0.95f)
        } else {
            Color(0xFFFF5252)
        }

        overlayGuide.screenshotUri?.let { uri ->
            AsyncImage(
                model = uri.toUri(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = overlayGuide.alpha,
                modifier = Modifier
                    .offset {
                        IntOffset(frameRect.left.toInt(), frameRect.top.toInt())
                    }
                    .requiredSize(
                        with(density) { frameRect.width.toDp() },
                        with(density) { frameRect.height.toDp() }
                    )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frame = Rect(frameRect.left, frameRect.top, frameRect.right, frameRect.bottom)
            drawRect(Color.Black.copy(alpha = 0.42f), topLeft = Offset.Zero, size = Size(size.width, frame.top))
            drawRect(
                Color.Black.copy(alpha = 0.42f),
                topLeft = Offset(0f, frame.bottom),
                size = Size(size.width, size.height - frame.bottom)
            )
            drawRect(
                Color.Black.copy(alpha = 0.42f),
                topLeft = Offset(0f, frame.top),
                size = Size(frame.left, frame.height)
            )
            drawRect(
                Color.Black.copy(alpha = 0.42f),
                topLeft = Offset(frame.right, frame.top),
                size = Size(size.width - frame.right, frame.height)
            )

            drawRect(
                color = borderColor,
                topLeft = frame.topLeft,
                size = frame.size,
                style = Stroke(width = 3.dp.toPx())
            )

            val gridColor = Color.White.copy(alpha = 0.25f)
            val thirdW = frame.width / 3f
            val thirdH = frame.height / 3f
            repeat(2) { index ->
                val x = frame.left + thirdW * (index + 1)
                drawLine(gridColor, Offset(x, frame.top), Offset(x, frame.bottom), strokeWidth = 1.dp.toPx())
                val y = frame.top + thirdH * (index + 1)
                drawLine(gridColor, Offset(frame.left, y), Offset(frame.right, y), strokeWidth = 1.dp.toPx())
            }
        }

        if (!coversFrame) {
            Text(
                text = "Blank area may appear inside the lock screen frame",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp)
                    .background(Color(0xCCB00020), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}

private data class FrameRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun calculateCenteredAspectRect(
    viewportWidth: Float,
    viewportHeight: Float,
    aspect: Float
): FrameRect {
    val widthFromHeight = viewportHeight * aspect
    val (frameWidth, frameHeight) = if (widthFromHeight <= viewportWidth) {
        widthFromHeight to viewportHeight
    } else {
        viewportWidth to viewportWidth / aspect
    }
    val left = (viewportWidth - frameWidth) / 2f
    val top = (viewportHeight - frameHeight) / 2f
    return FrameRect(left, top, left + frameWidth, top + frameHeight)
}

private fun calculateTransformedViewportRect(
    framing: WallpaperFraming,
    viewportWidth: Float,
    viewportHeight: Float
): FrameRect {
    val scaledWidth = viewportWidth * framing.scale
    val scaledHeight = viewportHeight * framing.scale
    val left = (viewportWidth - scaledWidth) / 2f + framing.offsetX * viewportWidth
    val top = (viewportHeight - scaledHeight) / 2f + framing.offsetY * viewportHeight
    return FrameRect(left, top, left + scaledWidth, top + scaledHeight)
}
