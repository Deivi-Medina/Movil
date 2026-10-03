package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.LocalSong
import java.io.File
import com.example.ui.theme.CosmicBorder
import com.example.ui.theme.CosmicCard
import com.example.ui.theme.CosmicSurface
import com.example.ui.theme.OfflineGreen
import com.example.ui.theme.OfflineGreenBg
import com.example.ui.theme.PurpleNeon
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private const val COVER_TAG = "CoverDebug"

@Composable
fun LocalSongRow(
    song: LocalSong,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("local_song_row_${song.id}")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) CosmicSurface else CosmicCard
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isPlaying) PurpleNeon else CosmicBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Portada / Icono musical
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmicSurface),
                contentAlignment = Alignment.Center
            ) {
                // FIX + DEBUG: Resolver el modelo del cover con logs
                val coverModel: Any? = remember(song.albumArtUri) {
                    val art = song.albumArtUri
                    android.util.Log.d(COVER_TAG, "═══════════════════════════════════")
                    android.util.Log.d(COVER_TAG, "🎵 Canción: '${song.title}'")
                    android.util.Log.d(COVER_TAG, "   albumArtUri = '$art'")

                    val result: Any? = when {
                        art.isNullOrEmpty() -> {
                            android.util.Log.d(COVER_TAG, "   → URI vacío → null")
                            null
                        }
                        art.startsWith("file://") -> {
                            val path = art.removePrefix("file://")
                            val file = File(path)
                            val exists = file.exists()
                            val length = file.length()
                            android.util.Log.d(COVER_TAG, "   → file:// detectado")
                            android.util.Log.d(COVER_TAG, "   path = '$path'")
                            android.util.Log.d(COVER_TAG, "   exists = $exists")
                            android.util.Log.d(COVER_TAG, "   length = $length bytes")
                            if (exists && length > 0) {
                                android.util.Log.d(COVER_TAG, "   ✅ Devolviendo File")
                                file
                            } else {
                                android.util.Log.d(COVER_TAG, "   ❌ null (no existe o vacío)")
                                null
                            }
                        }
                        art.startsWith("/") -> {
                            val file = File(art)
                            val exists = file.exists()
                            val length = file.length()
                            android.util.Log.d(COVER_TAG, "   → path absoluto")
                            android.util.Log.d(COVER_TAG, "   exists = $exists, length = $length")
                            if (exists && length > 0) {
                                android.util.Log.d(COVER_TAG, "   ✅ Devolviendo File")
                                file
                            } else {
                                android.util.Log.d(COVER_TAG, "   ❌ null")
                                null
                            }
                        }
                        else -> {
                            android.util.Log.d(COVER_TAG, "   → URL remota")
                            art
                        }
                    }
                    result
                }

                if (coverModel != null) {
                    android.util.Log.d(COVER_TAG, "   📷 Pidiendo a Coil: $coverModel")
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverModel)
                            .crossfade(true)
                            .listener(
                                onError = { _, result ->
                                    android.util.Log.e(COVER_TAG, "   ❌ Coil ERROR: ${result.throwable.message}")
                                },
                                onSuccess = { _, _ ->
                                    android.util.Log.d(COVER_TAG, "   ✅ Coil cargó OK")
                                }
                            )
                            .build(),
                        placeholder = painterResource(id = R.drawable.audiophiles_default_cover),
                        error = painterResource(id = R.drawable.audiophiles_default_cover),
                        fallback = painterResource(id = R.drawable.audiophiles_default_cover),
                        contentDescription = "Portada de ${song.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    android.util.Log.d(COVER_TAG, "   🎵 Mostrando icono musical (coverModel null)")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CosmicSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (isPlaying) PurpleNeon else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Superposición de ecualizador si está reproduciéndose
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x990A0A12)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Reproduciendo",
                            tint = PurpleNeon,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Información de la canción
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isPlaying) PurpleNeon else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (song.isDownloadedFromAudiophiles) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = OfflineGreenBg,
                            border = BorderStroke(1.dp, OfflineGreen.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = OfflineGreen,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "OFFLINE",
                                    color = OfflineGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (song.durationMs > 0) {
                        val durMin = song.durationMs / 1000 / 60
                        val durSec = (song.durationMs / 1000) % 60
                        Text(
                            text = "• %d:%02d".format(durMin, durSec),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    if (song.qualityLabel != null) {
                        Text(
                            text = "• ${song.qualityLabel}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // Botón de eliminar del celular si se proporciona callback
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Borrar canción del celular",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Botón de reproducción
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) PurpleNeon.copy(alpha = 0.2f) else CosmicSurface)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = if (isPlaying) PurpleNeon else TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}