package com.anthonyla.paperize.presentation.screens.library
import com.anthonyla.paperize.presentation.theme.AppIconSizes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.presentation.common.components.AddAlbumDialog
import com.anthonyla.paperize.presentation.screens.library.components.AlbumItem
import com.anthonyla.paperize.presentation.theme.AppGrid
import com.anthonyla.paperize.presentation.theme.AppSpacing

@Composable
fun LibraryScreen(
    albums: List<AlbumSummary>,
    onViewAlbum: (String) -> Unit,
    onCreateAlbum: (String) -> Unit,
    onRenameAlbum: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyGridState()
    var showAddAlbumDialog by rememberSaveable { mutableStateOf(false) }
    var albumNameError by rememberSaveable { mutableStateOf<String?>(null) }
    var albumBeingRenamed by rememberSaveable { mutableStateOf<AlbumSummary?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var renameError by rememberSaveable { mutableStateOf<String?>(null) }
    val albumNameExistsError = stringResource(R.string.album_name_exists_error)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            LargeFloatingActionButton(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = { showAddAlbumDialog = true },
                shape = MaterialTheme.shapes.large
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_album),
                    modifier = Modifier.size(AppIconSizes.medium)
                )
            }
        }
    ) { paddingValues ->
        if (albums.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSizes.huge),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = stringResource(R.string.no_albums_found),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.create_first_album_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                columns = GridCells.Adaptive(AppGrid.itemMinSize),
                contentPadding = PaddingValues(AppSpacing.gridPadding),
                horizontalArrangement = Arrangement.Start
            ) {
                items(albums, key = { album -> album.id }) { album ->
                    AlbumItem(
                        album = album,
                        onAlbumViewClick = { onViewAlbum(album.id) },
                        onRenameClick = {
                            albumBeingRenamed = album
                            renameText = album.name
                            renameError = null
                        },
                        modifier = Modifier.padding(AppSpacing.gridPadding)
                    )
                }
            }
        }
    }

    albumBeingRenamed?.let { album ->
        AlertDialog(
            onDismissRequest = {
                albumBeingRenamed = null
                renameError = null
            },
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = {
                        renameText = it
                        renameError = null
                    },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = { renameError?.let { Text(it) } }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameText.trim()
                    val duplicate = albums.any { it.id != album.id && it.name.equals(trimmed, ignoreCase = true) }
                    when {
                        trimmed.isBlank() -> renameError = "Album name cannot be empty"
                        duplicate -> renameError = albumNameExistsError
                        else -> {
                            onRenameAlbum(album.id, trimmed)
                            albumBeingRenamed = null
                            renameError = null
                        }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { albumBeingRenamed = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showAddAlbumDialog) {
        AddAlbumDialog(
            onDismiss = {
                showAddAlbumDialog = false
                albumNameError = null
            },
            onConfirm = { name ->
                // Check if album with this name already exists
                val isDuplicate = albums.any { it.name.equals(name, ignoreCase = true) }
                if (isDuplicate) {
                    albumNameError = albumNameExistsError
                } else {
                    onCreateAlbum(name)
                    showAddAlbumDialog = false
                    albumNameError = null
                }
            },
            errorMessage = albumNameError
        )
    }
}
