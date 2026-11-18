package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ArtistChartItem
import com.example.data.model.MediaTrack
import com.example.ui.components.GroupedTrackRow
import com.example.ui.components.formatPlayCount

@Composable
fun SearchScreen(
    searchQuery: String,
    chartTracks: List<MediaTrack>,
    trendingArtists: List<ArtistChartItem>,
    currentTrackId: String?,
    chartPage: Int,
    chartRegionLabel: String = "System Default",
    onSearchQueryChange: (String) -> Unit,
    onOpenDownloaderSheet: (String) -> Unit,
    onPageChange: (Int, Int) -> Unit,
    onPlayTrack: (MediaTrack, List<MediaTrack>) -> Unit,
    onPlayNext: (MediaTrack) -> Unit,
    onAddToPlaylist: (MediaTrack) -> Unit,
    onToggleLike: (MediaTrack) -> Unit,
    onConvertOrExtract: (MediaTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUrlInput = searchQuery.trim().startsWith("http://", ignoreCase = true) ||
        searchQuery.trim().startsWith("https://", ignoreCase = true)

    val pageSize = 5
    val totalPages = 6
    val safePage = chartPage.coerceIn(0, totalPages - 1)

    // Ensure 6 interactive pages of 5 ranked tracks (Top 30 / Top 100 slice)
    val pageItems: List<Pair<Int, MediaTrack>> = if (chartTracks.isEmpty()) {
        emptyList()
    } else {
        (0 until pageSize).mapNotNull { offset ->
            val rankIndex = safePage * pageSize + offset
            val track = chartTracks[rankIndex % chartTracks.size]
            (rankIndex + 1) to track
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen"),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // Top Stadium-Pill Search & URL Bar
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (isUrlInput) {
                                        onOpenDownloaderSheet(searchQuery.trim())
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_url_input")
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = { onOpenDownloaderSheet(searchQuery.trim()) },
                        modifier = Modifier.testTag("search_globe_download_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = stringResource(R.string.action_download),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            if (isUrlInput) {
                Button(
                    onClick = { onOpenDownloaderSheet(searchQuery.trim()) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("url_detected_download_btn")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Media from URL")
                }
            }
        }

        // Charts Header ("Top 100" / "System Default")
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.top_100),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = chartRegionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 5-item grouped ranked list
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                pageItems.forEachIndexed { index, (rank, track) ->
                    GroupedTrackRow(
                        track = track,
                        index = index,
                        totalCount = pageItems.size,
                        isActive = track.id == currentTrackId,
                        leadingRank = rank,
                        subtitleOverride = "${track.artist} • ${formatPlayCount(track.playCount)}",
                        onClick = { onPlayTrack(track, chartTracks) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onToggleLike = { onToggleLike(track) },
                        onConvertOrExtract = { onConvertOrExtract(track) }
                    )
                }
            }
        }

        // Centered pill pagination control (<  1 of 6  >)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onPageChange(safePage - 1, totalPages - 1) },
                            enabled = safePage > 0,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("chart_prev_page")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = "Previous chart page"
                            )
                        }
                        Text(
                            text = "${safePage + 1} of $totalPages",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        IconButton(
                            onClick = { onPageChange(safePage + 1, totalPages - 1) },
                            enabled = safePage < totalPages - 1,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("chart_next_page")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "Next chart page"
                            )
                        }
                    }
                }
            }
        }

        // Trending Artists Row
        item {
            Text(
                text = stringResource(R.string.trending_artists),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(trendingArtists) { artist ->
                    TrendingArtistCard(
                        artist = artist,
                        onClick = {
                            onSearchQueryChange(artist.name)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendingArtistCard(
    artist: ArtistChartItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("trending_artist_${artist.rank}")
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Image(
                painter = painterResource(id = artist.artworkResId),
                contentDescription = artist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = artist.rank.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = artist.monthlyListeners,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
