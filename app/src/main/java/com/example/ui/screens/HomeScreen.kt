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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.MediaTrack
import com.example.ui.EchoTopSheetType
import com.example.ui.components.EchoTopBar
import com.example.ui.components.GroupedTrackRow
import com.example.ui.components.SectionHeaderWithPlayAll
import com.example.ui.components.cleanArtistLabel

private val moodTags = listOf("Feel good", "Romance", "Relax", "Party", "Energy")

@Composable
fun HomeScreen(
    tracks: List<MediaTrack>,
    currentTrackId: String?,
    selectedMood: String?,
    onSelectMood: (String) -> Unit,
    onTopBarAction: (EchoTopSheetType) -> Unit,
    onLogoClick: () -> Unit = {},
    onPlayTrack: (MediaTrack, List<MediaTrack>) -> Unit,
    onPlayNext: (MediaTrack) -> Unit,
    onAddToPlaylist: (MediaTrack) -> Unit,
    onToggleLike: (MediaTrack) -> Unit,
    onConvertOrExtract: (MediaTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredTracks = if (selectedMood == null) {
        tracks
    } else {
        tracks.filter { it.moodTag.equals(selectedMood, ignoreCase = true) }.ifEmpty { tracks }
    }

    val heroTracks = filteredTracks.take(5)
    val forgottenFavorites = filteredTracks.take(4)
    val stationTracks = filteredTracks.drop(4).take(4).ifEmpty { filteredTracks.take(4) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            EchoTopBar(
                title = stringResource(R.string.app_name),
                onActionClick = onTopBarAction,
                onLogoClick = onLogoClick
            )
        }

        // Mood Filter Row
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moodTags) { mood ->
                    val isSelected = selectedMood == mood
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectMood(mood) },
                        label = {
                            Text(
                                text = mood,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.testTag("mood_chip_${mood.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }

        // Hero Carousel with peeking neighboring cards
        if (heroTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                val pagerState = rememberPagerState(pageCount = { heroTracks.size })
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 36.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_hero_carousel")
                ) { page ->
                    val track = heroTracks[page]
                    HeroCarouselCard(
                        track = track,
                        onClick = { onPlayTrack(track, filteredTracks) }
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // Section 1: FORGOTTEN FAVORITES
        item {
            SectionHeaderWithPlayAll(
                title = stringResource(R.string.forgotten_favorites),
                onPlayAll = {
                    forgottenFavorites.firstOrNull()?.let { onPlayTrack(it, forgottenFavorites) }
                }
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                forgottenFavorites.forEachIndexed { index, track ->
                    GroupedTrackRow(
                        track = track,
                        index = index,
                        totalCount = forgottenFavorites.size,
                        isActive = track.id == currentTrackId,
                        onClick = { onPlayTrack(track, forgottenFavorites) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onToggleLike = { onToggleLike(track) },
                        onConvertOrExtract = { onConvertOrExtract(track) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Section 2: STATION / LISTEN TOGETHER
        item {
            SectionHeaderWithPlayAll(
                title = stringResource(R.string.station_listen_together),
                onPlayAll = {
                    stationTracks.firstOrNull()?.let { onPlayTrack(it, stationTracks) }
                }
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                stationTracks.forEachIndexed { index, track ->
                    GroupedTrackRow(
                        track = track,
                        index = index,
                        totalCount = stationTracks.size,
                        isActive = track.id == currentTrackId,
                        onClick = { onPlayTrack(track, stationTracks) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onToggleLike = { onToggleLike(track) },
                        onConvertOrExtract = { onConvertOrExtract(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCarouselCard(
    track: MediaTrack,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("hero_card_${track.id}"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!track.artworkUriString.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUriString,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    error = painterResource(
                        id = track.artworkResId ?: R.drawable.img_cover_midnight_1794995218900
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    painter = painterResource(
                        id = track.artworkResId ?: R.drawable.img_cover_midnight_1794995218900
                    ),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom-left dark gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = track.moodTag.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = cleanArtistLabel(track),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.action_play_pause),
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
