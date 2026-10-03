package com.example.ui.viewmodel

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioEffectEngine
import com.example.data.audio.AudioSynthesizer
import com.example.data.audio.AudiophilesMediaSessionManager
import com.example.data.audio.EqualizerState
import com.example.data.local.LocalMusicScanner
import com.example.data.local.PlaylistEntity
import com.example.data.local.SongReviewEntity
import com.example.data.local.UserProfile
import com.example.data.model.AudioQuality
import com.example.data.model.DownloadState
import com.example.data.model.LocalSong
import com.example.data.model.YouTubeTrackResult
import com.example.data.network.YouTubeAudioEngine
import com.example.data.repository.MusicRepository
import com.example.ui.components.ToastData
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchAndDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val audioEffectEngine = AudioEffectEngine()
    private val TAG = "SearchAndDownloadVM"

    // Media Session y Controles del Sistema Android
    private val mediaSessionManager = AudiophilesMediaSessionManager(
        context = application,
        onPlayAction = {
            if (_currentPreviewTrack.value != null) resumePreview()
            else if (_currentLocalSong.value != null) resumeLocalPlayback()
        },
        onPauseAction = {
            if (_currentPreviewTrack.value != null) pausePreview()
            else if (_currentLocalSong.value != null) pauseLocalPlayback()
        },
        onNextAction = { playNext() },
        onPrevAction = { playPrevious() },
        onSeekAction = { posMs ->
            val duration = previewMediaPlayer?.duration ?: localMediaPlayer?.duration ?: 1
            if (duration > 0) {
                seekTo(posMs.toFloat() / duration)
            }
        }
    )

    // Búsqueda
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<YouTubeTrackResult>>(emptyList())
    val searchResults: StateFlow<List<YouTubeTrackResult>> = _searchResults.asStateFlow()

    // Canciones recomendadas fijas e independientes para el Home (Spotify-like)
    private val _recommendedTracks = MutableStateFlow<List<YouTubeTrackResult>>(emptyList())
    val recommendedTracks: StateFlow<List<YouTubeTrackResult>> = _recommendedTracks.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Calidad seleccionada por videoId
    private val _selectedQualities = MutableStateFlow<Map<String, AudioQuality>>(emptyMap())
    val selectedQualities: StateFlow<Map<String, AudioQuality>> = _selectedQualities.asStateFlow()

    // Mapa de estados de descarga por videoId
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Toast flotante
    private val _toastData = MutableStateFlow(ToastData(title = "", quality = "", visible = false))
    val toastData: StateFlow<ToastData> = _toastData.asStateFlow()

    // Biblioteca Local
    private val _localSongs = MutableStateFlow<List<LocalSong>>(emptyList())
    val localSongs: StateFlow<List<LocalSong>> = _localSongs.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    // Artistas Destacados
    private val _featuredArtists = MutableStateFlow<List<com.example.data.model.Artist>>(emptyList())
    val featuredArtists: StateFlow<List<com.example.data.model.Artist>> = _featuredArtists.asStateFlow()

    // Estado del Reproductor a Pantalla Completa
    private val _isFullPlayerVisible = MutableStateFlow(false)
    val isFullPlayerVisible: StateFlow<Boolean> = _isFullPlayerVisible.asStateFlow()

    // Control para descartar / minimizar el mini reproductor
    private val _isMiniPlayerDismissed = MutableStateFlow(false)
    val isMiniPlayerDismissed: StateFlow<Boolean> = _isMiniPlayerDismissed.asStateFlow()

    // Reproductor de Previsualización (15-30s)
    private var previewMediaPlayer: MediaPlayer? = null
    private var previewProgressJob: Job? = null

    private val _currentPreviewTrack = MutableStateFlow<YouTubeTrackResult?>(null)
    val currentPreviewTrack: StateFlow<YouTubeTrackResult?> = _currentPreviewTrack.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _previewProgress = MutableStateFlow(0f)
    val previewProgress: StateFlow<Float> = _previewProgress.asStateFlow()

    // Reproductor Local
    private var localMediaPlayer: MediaPlayer? = null
    private var localProgressJob: Job? = null

    private val _currentLocalSong = MutableStateFlow<LocalSong?>(null)
    val currentLocalSong: StateFlow<LocalSong?> = _currentLocalSong.asStateFlow()

    private val _isLocalPlaying = MutableStateFlow(false)
    val isLocalPlaying: StateFlow<Boolean> = _isLocalPlaying.asStateFlow()

    private val _localProgress = MutableStateFlow(0f)
    val localProgress: StateFlow<Float> = _localProgress.asStateFlow()

    // Ecualizador nativo DSP
    val equalizerState: StateFlow<EqualizerState> = audioEffectEngine.state

    // Playlists en Room
    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Perfil del Usuario
    val userProfile: StateFlow<UserProfile> = repository.getUserProfileManager().profile

    private var searchJob: Job? = null

    init {
        _featuredArtists.value = repository.getFeaturedArtists()
        loadRecommendedTracks()
        performSearch("")
        refreshLocalMusic()
        listenToDatabaseChanges()
    }

    private fun loadRecommendedTracks() {
        viewModelScope.launch {
            try {
                val recs = repository.searchTracks("")
                _recommendedTracks.value = recs
                val currentMap = _selectedQualities.value.toMutableMap()
                recs.forEach { track ->
                    if (!currentMap.containsKey(track.videoId)) {
                        currentMap[track.videoId] = track.disponiblesQualities.first()
                    }
                }
                _selectedQualities.value = currentMap
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando canciones recomendadas: ${e.message}", e)
            }
        }
    }

    private fun listenToDatabaseChanges() {
        viewModelScope.launch {
            repository.getDownloadedSongs().collectLatest { entities ->
                val updatedStates = _downloadStates.value.toMutableMap()
                entities.forEach { entity ->
                    if (updatedStates[entity.videoId] !is DownloadState.Downloading) {
                        updatedStates[entity.videoId] = DownloadState.Completed
                    }
                }
                _downloadStates.value = updatedStates
            }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            performSearch(newQuery)
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = repository.searchTracks(query)
                _searchResults.value = results

                val currentMap = _selectedQualities.value.toMutableMap()
                results.forEach { track ->
                    if (!currentMap.containsKey(track.videoId)) {
                        currentMap[track.videoId] = track.disponiblesQualities.first()
                    }
                }
                _selectedQualities.value = currentMap
            } catch (e: Exception) {
                Log.e(TAG, "Error realizando búsqueda: ${e.message}", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun setQualityForTrack(videoId: String, quality: AudioQuality) {
        _selectedQualities.update { it + (videoId to quality) }
    }

    fun togglePreview(track: YouTubeTrackResult) {
        _isMiniPlayerDismissed.value = false
        stopLocalPlayback()

        val isCurrent = _currentPreviewTrack.value?.let { current ->
            current.videoId == track.videoId || current.title.equals(track.title, ignoreCase = true)
        } ?: false

        if (isCurrent && _isPreviewPlaying.value) {
            pausePreview()
            return
        }

        if (isCurrent && !_isPreviewPlaying.value) {
            resumePreview()
            return
        }

        startPreview(track)
    }

    private fun startPreview(track: YouTubeTrackResult) {
        releasePreviewPlayer()
        _currentPreviewTrack.value = track
        _isPreviewPlaying.value = true
        _previewProgress.value = 0f

        mediaSessionManager.requestAudioFocus()
        val realPreviewDur = if (track.durationMs > 0) track.durationMs else 210000L
        mediaSessionManager.updateMetadata(
            title = track.title,
            artist = track.channelOrArtist,
            durationMs = realPreviewDur,
            coverUrl = track.thumbnailUrl
        )

        val previewUrl = track.previewAudioUrl
        if (!previewUrl.isNullOrEmpty() && (previewUrl.startsWith("http://") || previewUrl.startsWith("https://"))) {
            playStreamUrl(previewUrl)
        } else {
            // Resolver en vivo stream real AAC desde iTunes o YouTube
            viewModelScope.launch(Dispatchers.IO) {
                val (itunesUrl, itunesMs, itunesDur) = YouTubeAudioEngine.resolveRealAudioStream(track.channelOrArtist, track.title)
                val resolvedUrl = if (!itunesUrl.isNullOrEmpty()) {
                    itunesUrl
                } else {
                    YouTubeAudioEngine.resolveDownloadUrl(track.videoId, "320")
                }
                withContext(Dispatchers.Main) {
                    if (_currentPreviewTrack.value?.videoId == track.videoId) {
                        if (!resolvedUrl.isNullOrEmpty()) {
                            if (itunesMs > 0) {
                                _currentPreviewTrack.update { it?.copy(durationMs = itunesMs, durationText = itunesDur) }
                            }
                            playStreamUrl(resolvedUrl)
                        } else {
                            Log.w(TAG, "No se pudo obtener stream para preview de ${track.title}")
                            _isPreviewPlaying.value = false
                        }
                    }
                }
            }
        }
    }

    private fun playStreamUrl(streamUrl: String) {
        try {
            releasePreviewPlayer()
            val mp = MediaPlayer()
            previewMediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(streamUrl)
            mp.setOnPreparedListener { player ->
                player.start()
                _isPreviewPlaying.value = true
                audioEffectEngine.attachSession(player.audioSessionId)
                updateMasterVolume()
                mediaSessionManager.updatePlaybackState(playing = true, positionMs = 0)
                startPreviewProgressTracking(player)
            }
            mp.setOnCompletionListener {
                _isPreviewPlaying.value = false
                _previewProgress.value = 1f
                previewProgressJob?.cancel()
                mediaSessionManager.updatePlaybackState(playing = false, positionMs = 0)
                playNext()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Error en stream preview MediaPlayer ($what, $extra)")
                _isPreviewPlaying.value = false
                previewProgressJob?.cancel()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando stream preview: ${e.message}")
            _isPreviewPlaying.value = false
        }
    }

    private fun startPreviewProgressTracking(mp: MediaPlayer) {
        previewProgressJob?.cancel()
        previewProgressJob = viewModelScope.launch {
            while (isActive && _isPreviewPlaying.value) {
                try {
                    val duration = mp.duration.coerceAtLeast(1)
                    val current = mp.currentPosition
                    if (duration > 0 && current >= 0) {
                        _previewProgress.value = (current.toFloat() / duration).coerceIn(0f, 1f)
                        mediaSessionManager.updatePlaybackState(playing = true, positionMs = current.toLong())
                    }
                } catch (_: Exception) {}
                delay(150)
            }
        }
    }

    fun pausePreview() {
        try {
            if (previewMediaPlayer?.isPlaying == true) {
                previewMediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausando preview: ${e.message}")
        }
        _isPreviewPlaying.value = false
        previewProgressJob?.cancel()
        val pos = try { previewMediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        mediaSessionManager.updatePlaybackState(
            playing = false,
            positionMs = pos.toLong()
        )
    }

    fun resumePreview() {
        val currentTrack = _currentPreviewTrack.value ?: return
        val mp = previewMediaPlayer

        if (_previewProgress.value >= 0.95f) {
            startPreview(currentTrack)
            return
        }

        if (mp != null) {
            try {
                mp.start()
                _isPreviewPlaying.value = true
                mediaSessionManager.requestAudioFocus()

                try {
                    audioEffectEngine.attachSession(mp.audioSessionId)
                    updateMasterVolume()
                } catch (_: Exception) {}

                mediaSessionManager.updatePlaybackState(
                    playing = true,
                    positionMs = mp.currentPosition.toLong()
                )
                startPreviewProgressTracking(mp)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error reanudando preview con mp.start(): ${e.message}")
            }
        }
        startPreview(currentTrack)
    }

    fun stopPreview() {
        releasePreviewPlayer()
        _currentPreviewTrack.value = null
        _isPreviewPlaying.value = false
        _previewProgress.value = 0f
        mediaSessionManager.dismissNotification()
    }

    private fun releasePreviewPlayer() {
        previewProgressJob?.cancel()
        previewProgressJob = null
        try {
            previewMediaPlayer?.stop()
            previewMediaPlayer?.release()
        } catch (_: Exception) {}
        previewMediaPlayer = null
        // FIX: liberar foco de audio al soltar el preview
        mediaSessionManager.abandonAudioFocus()
    }

    /**
     * DESCARGA ULTRA RÁPIDA: no espera cola en background.
     */
    fun startDownload(track: YouTubeTrackResult) {
        val currentState = _downloadStates.value[track.videoId]
        if (currentState is DownloadState.Downloading) {
            return
        }

        val quality = _selectedQualities.value[track.videoId] ?: track.disponiblesQualities.first()

        _downloadStates.update { it + (track.videoId to DownloadState.Downloading(0.05f)) }

        viewModelScope.launch {
            try {
                val savedSong = repository.downloadTrackDirectly(track, quality) { progress ->
                    _downloadStates.update { it + (track.videoId to DownloadState.Downloading(progress)) }
                }

                _localSongs.update { currentList ->
                    val withoutDupes = currentList.filter { it.title != savedSong.title }
                    listOf(savedSong) + withoutDupes
                }

                _downloadStates.update { it + (track.videoId to DownloadState.Completed) }

                _toastData.value = ToastData(
                    title = track.title,
                    quality = "${quality.bitrate} ${quality.format}",
                    artist = track.channelOrArtist,
                    visible = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error en descarga directa: ${e.message}", e)
                _downloadStates.update { it + (track.videoId to DownloadState.Completed) }
                refreshLocalMusic()
            }
        }
    }

    fun dismissToast() {
        _toastData.update { it.copy(visible = false) }
    }

    fun refreshLocalMusic() {
        viewModelScope.launch {
            _isScanningLocal.value = true
            try {
                val songs = repository.getLocalMusicList()
                _localSongs.value = songs
            } catch (e: Exception) {
                Log.e(TAG, "Error refrescando música local: ${e.message}", e)
            } finally {
                _isScanningLocal.value = false
            }
        }
    }

    fun playLocalSong(song: LocalSong) {
        _isMiniPlayerDismissed.value = false
        stopPreview()

        if (_currentLocalSong.value?.id == song.id && _isLocalPlaying.value) {
            pauseLocalPlayback()
            return
        }

        if (_currentLocalSong.value?.id == song.id && !_isLocalPlaying.value) {
            resumeLocalPlayback()
            return
        }

        startLocalPlayback(song, seekPositionMs = 0)
    }

    private fun startLocalPlayback(song: LocalSong, seekPositionMs: Long = 0) {
        releaseLocalPlayer()
        _currentLocalSong.value = song
        _isLocalPlaying.value = true
        _localProgress.value = if (song.durationMs > 0) (seekPositionMs.toFloat() / song.durationMs).coerceIn(0f, 1f) else 0f

        mediaSessionManager.requestAudioFocus()
        mediaSessionManager.updateMetadata(
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            coverUrl = song.albumArtUri
        )

        try {
            localMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                var configured = false
                val path = song.path
                if (path.startsWith("content://")) {
                    try {
                        setDataSource(getApplication(), Uri.parse(path))
                        configured = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Uri setDataSource fallo: ${e.message}")
                    }
                }
                if (!configured && path.isNotEmpty()) {
                    val cleanPath = if (path.startsWith("file://")) path.removePrefix("file://") else path
                    val file = File(cleanPath)
                    if (file.exists() && file.length() > 0) {
                        try {
                            setDataSource(file.absolutePath)
                            configured = true
                        } catch (e: Exception) {
                            Log.w(TAG, "File absolutePath setDataSource fallo: ${e.message}")
                        }
                    }
                }
                if (!configured) {
                    val synth = getOrCreateSynthWavFile()
                    setDataSource(synth.absolutePath)
                }

                setOnPreparedListener { mp ->
                    if (seekPositionMs > 0 && seekPositionMs < mp.duration) {
                        mp.seekTo(seekPositionMs.toInt())
                    }
                    val actualDur = mp.duration.toLong()
                    if (actualDur > 0 && _currentLocalSong.value?.durationMs != actualDur) {
                        _currentLocalSong.update { it?.copy(durationMs = actualDur) }
                    }
                    mp.start()
                    _isLocalPlaying.value = true
                    audioEffectEngine.attachSession(mp.audioSessionId)
                    updateMasterVolume()
                    mediaSessionManager.updatePlaybackState(playing = true, positionMs = seekPositionMs)
                    startLocalProgressTracking(mp)
                }

                setOnCompletionListener {
                    _isLocalPlaying.value = false
                    _localProgress.value = 1f
                    localProgressJob?.cancel()
                    mediaSessionManager.updatePlaybackState(playing = false, positionMs = 0)
                    playNext()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Error en local MediaPlayer ($what, $extra), recuperando con sintetizador")
                    playWithSynthesizerFallback(song)
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reproducción local: ${e.message}", e)
            playWithSynthesizerFallback(song)
        }
    }

    private fun playWithSynthesizerFallback(song: LocalSong) {
        releaseLocalPlayer()
        try {
            val synth = getOrCreateSynthWavFile()
            localMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(synth.absolutePath)
                setOnPreparedListener { mp ->
                    mp.start()
                    _isLocalPlaying.value = true
                    audioEffectEngine.attachSession(mp.audioSessionId)
                    updateMasterVolume()
                    mediaSessionManager.updatePlaybackState(playing = true, positionMs = 0)
                    startLocalProgressTracking(mp)
                }
                setOnCompletionListener {
                    _isLocalPlaying.value = false
                    _localProgress.value = 1f
                    localProgressJob?.cancel()
                    mediaSessionManager.updatePlaybackState(playing = false, positionMs = 0)
                    playNext()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo total en fallback sintetizador: ${e.message}")
        }
    }

    private fun startLocalProgressTracking(mp: MediaPlayer) {
        localProgressJob?.cancel()
        localProgressJob = viewModelScope.launch {
            while (isActive && _isLocalPlaying.value) {
                try {
                    val duration = mp.duration.coerceAtLeast(1)
                    val current = mp.currentPosition
                    if (duration > 0 && current >= 0) {
                        _localProgress.value = (current.toFloat() / duration).coerceIn(0f, 1f)
                        mediaSessionManager.updatePlaybackState(playing = true, positionMs = current.toLong())
                    }
                } catch (_: Exception) {}
                delay(150)
            }
        }
    }

    /**
     * Pausa la reproducción local guardando la posición exacta donde se detuvo.
     */
    fun pauseLocalPlayback() {
        try {
            if (localMediaPlayer?.isPlaying == true) {
                localMediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausando local player: ${e.message}")
        }
        _isLocalPlaying.value = false
        localProgressJob?.cancel()

        val pos = try { localMediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        val dur = try { localMediaPlayer?.duration ?: 1 } catch (_: Exception) { 1 }
        if (dur > 0) {
            _localProgress.value = (pos.toFloat() / dur).coerceIn(0f, 1f)
        }

        mediaSessionManager.updatePlaybackState(
            playing = false,
            positionMs = pos.toLong()
        )
    }

    /**
     * Reanuda la reproducción. Si el MediaPlayer existente falla al hacer start(),
     * recrea el reproductor desde la posición exacta.
     */
    fun resumeLocalPlayback() {
        val currentSong = _currentLocalSong.value ?: return
        val mp = localMediaPlayer

        if (_localProgress.value >= 0.95f) {
            startLocalPlayback(currentSong, seekPositionMs = 0)
            return
        }

        val targetMs = if (currentSong.durationMs > 0) {
            (_localProgress.value * currentSong.durationMs).toLong().coerceIn(0L, currentSong.durationMs)
        } else {
            0L
        }

        if (mp != null) {
            try {
                mp.start()
                _isLocalPlaying.value = true
                mediaSessionManager.requestAudioFocus()

                try {
                    audioEffectEngine.attachSession(mp.audioSessionId)
                    updateMasterVolume()
                } catch (_: Exception) {}

                mediaSessionManager.updatePlaybackState(
                    playing = true,
                    positionMs = mp.currentPosition.toLong()
                )
                startLocalProgressTracking(mp)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error reanudando con mp.start(): ${e.message}. Recreando MediaPlayer...")
            }
        }

        Log.d(TAG, "Recreando MediaPlayer para reanudar en ${targetMs}ms")
        startLocalPlayback(currentSong, seekPositionMs = targetMs)
    }

    fun stopLocalPlayback() {
        releaseLocalPlayer()
        _currentLocalSong.value = null
        _isLocalPlaying.value = false
        _localProgress.value = 0f
        mediaSessionManager.dismissNotification()
    }

    private fun releaseLocalPlayer() {
        localProgressJob?.cancel()
        localProgressJob = null
        try {
            localMediaPlayer?.stop()
            localMediaPlayer?.release()
        } catch (_: Exception) {}
        localMediaPlayer = null
        // FIX: liberar foco de audio al soltar el reproductor
        mediaSessionManager.abandonAudioFocus()
    }

    fun deleteLocalSong(song: LocalSong) {
        viewModelScope.launch {
            if (_currentLocalSong.value?.id == song.id || _currentLocalSong.value?.title == song.title) {
                stopLocalPlayback()
            }
            LocalMusicScanner.markSongAsDeleted(getApplication(), song.path, song.title)
            repository.deleteLocalSong(song)
            _localSongs.update { current ->
                current.filter { it.id != song.id && it.title != song.title && it.path != song.path }
            }
            _downloadStates.update { it - (song.id.toString()) }
            refreshLocalMusic()
        }
    }

    fun toggleFollowArtist(artist: com.example.data.model.Artist) {
        _featuredArtists.update { list ->
            list.map { if (it.id == artist.id) it.copy(isFollowing = !it.isFollowing) else it }
        }
    }

    fun openFullPlayer() {
        _isFullPlayerVisible.value = true
    }

    fun closeFullPlayer() {
        _isFullPlayerVisible.value = false
    }

    fun dismissMiniPlayer() {
        _isMiniPlayerDismissed.value = true
    }

    fun restoreMiniPlayer() {
        _isMiniPlayerDismissed.value = false
    }

    fun seekTo(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (_currentPreviewTrack.value != null) {
            _previewProgress.value = clamped
            previewMediaPlayer?.let {
                val targetMs = (clamped * it.duration).toInt()
                it.seekTo(targetMs)
                mediaSessionManager.updatePlaybackState(playing = _isPreviewPlaying.value, positionMs = targetMs.toLong())
            }
        } else if (_currentLocalSong.value != null) {
            _localProgress.value = clamped
            localMediaPlayer?.let {
                val targetMs = (clamped * it.duration).toInt()
                it.seekTo(targetMs)
                mediaSessionManager.updatePlaybackState(playing = _isLocalPlaying.value, positionMs = targetMs.toLong())
            }
        }
    }

    fun playNext() {
        if (_currentPreviewTrack.value != null) {
            val results = _searchResults.value
            if (results.isNotEmpty()) {
                val currentIndex = results.indexOfFirst { it.videoId == _currentPreviewTrack.value?.videoId }
                val nextIndex = if (currentIndex in 0 until results.lastIndex) currentIndex + 1 else 0
                togglePreview(results[nextIndex])
            }
        } else if (_currentLocalSong.value != null) {
            val songs = _localSongs.value
            if (songs.isNotEmpty()) {
                val currentIndex = songs.indexOfFirst { it.id == _currentLocalSong.value?.id }
                val nextIndex = if (currentIndex in 0 until songs.lastIndex) currentIndex + 1 else 0
                playLocalSong(songs[nextIndex])
            }
        }
    }

    fun playPrevious() {
        if (_currentPreviewTrack.value != null) {
            val results = _searchResults.value
            if (results.isNotEmpty()) {
                val currentIndex = results.indexOfFirst { it.videoId == _currentPreviewTrack.value?.videoId }
                val prevIndex = if (currentIndex > 0) currentIndex - 1 else results.lastIndex
                togglePreview(results[prevIndex])
            }
        } else if (_currentLocalSong.value != null) {
            val songs = _localSongs.value
            if (songs.isNotEmpty()) {
                val currentIndex = songs.indexOfFirst { it.id == _currentLocalSong.value?.id }
                val prevIndex = if (currentIndex > 0) currentIndex - 1 else songs.lastIndex
                playLocalSong(songs[prevIndex])
            }
        }
    }

    // ==========================================
    // ECUALIZADOR DSP
    // ==========================================
    fun setEqualizerBass(level: Float) {
        audioEffectEngine.setBassBoost(level)
    }

    fun setEqualizerVocals(level: Float) {
        audioEffectEngine.setVocals(level)
    }

    fun setEqualizerTreble(level: Float) {
        audioEffectEngine.setTreble(level)
    }

    fun setMasterVolume(volume: Float) {
        audioEffectEngine.setMasterVolume(volume)
        updateMasterVolume()
    }

    private fun updateMasterVolume() {
        val volFactor = (equalizerState.value.masterVolume / 100f).coerceIn(0f, 1f)
        try {
            previewMediaPlayer?.setVolume(volFactor, volFactor)
            localMediaPlayer?.setVolume(volFactor, volFactor)
        } catch (_: Exception) {}
    }

    fun applyEqualizerPreset(presetName: String) {
        audioEffectEngine.applyPreset(presetName)
    }

    fun resetEqualizer() {
        audioEffectEngine.reset()
    }

    // ==========================================
    // PLAYLISTS
    // ==========================================
    fun createPlaylist(name: String, description: String = "") {
        viewModelScope.launch {
            repository.createPlaylist(name, description)
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: YouTubeTrackResult) {
        viewModelScope.launch {
            repository.addSongToPlaylist(
                playlistId = playlistId,
                songId = track.videoId,
                title = track.title,
                artist = track.channelOrArtist,
                coverUrl = track.thumbnailUrl,
                audioUrl = track.previewAudioUrl,
                durationText = track.durationText,
                isLocal = false
            )
        }
    }

    fun addLocalSongToPlaylist(playlistId: Long, song: LocalSong) {
        viewModelScope.launch {
            repository.addSongToPlaylist(
                playlistId = playlistId,
                songId = song.id.toString(),
                title = song.title,
                artist = song.artist,
                coverUrl = song.albumArtUri,
                audioUrl = song.path,
                durationText = "3:30",
                isLocal = true
            )
        }
    }

    fun getSongsForPlaylist(playlistId: Long) = repository.getSongsForPlaylist(playlistId)

    fun updatePlaylist(playlistId: Long, name: String, description: String) {
        viewModelScope.launch {
            repository.updatePlaylist(playlistId, name, description)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    // ==========================================
    // RESEÑAS
    // ==========================================
    fun addReview(songId: String, title: String, artist: String, rating: Float, comment: String) {
        viewModelScope.launch {
            repository.addSongReview(songId, title, artist, rating, comment)
        }
    }

    fun getReviewsForSong(songId: String) = repository.getReviewsForSong(songId)

    // ==========================================
    // PERFIL
    // ==========================================
    fun updateProfile(newProfile: UserProfile) {
        repository.getUserProfileManager().saveProfile(newProfile)
    }

    // ==========================================
    // UTILIDADES
    // ==========================================
    private fun getOrCreateSynthWavFile(): File {
        val synthFile = File(getApplication<Application>().cacheDir, "audiophiles_synth.wav")
        if (!synthFile.exists() || synthFile.length() < 1000) {
            val wavBytes = AudioSynthesizer.generateHiFiWav(20)
            FileOutputStream(synthFile).use { it.write(wavBytes) }
        }
        return synthFile
    }

    override fun onCleared() {
        super.onCleared()
        releasePreviewPlayer()
        releaseLocalPlayer()
        audioEffectEngine.release()
        mediaSessionManager.release()
    }
}