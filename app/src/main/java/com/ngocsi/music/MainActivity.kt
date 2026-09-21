package com.ngocsi.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

private val Black = Color(0xFF0B0B0B)
private val Card = Color(0xFF17171D)
private val White = Color(0xFFF5F5F5)
private val Gray = Color(0xFF9A9AA3)
private val Accent = Color(0xFFB58CFF)

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String
)

class MainActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            loadMusic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectToPlayer()

        setContent {
            NgocSiMusicApp(
                controllerProvider = { controller },
                onScanMusic = {
                    requestMusicPermission()
                }
            )
        }
    }

    private fun connectToPlayer() {

        val token = SessionToken(
            this,
            ComponentName(
                this,
                MusicService::class.java
            )
        )

        controllerFuture =
            MediaController.Builder(
                this,
                token
            ).buildAsync()

        controllerFuture?.addListener(
            {
                controller = controllerFuture?.get()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun requestMusicPermission() {

        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33) {

            permissions.add(
                Manifest.permission.READ_MEDIA_AUDIO
            )

        } else {

            permissions.add(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        val missingPermissions =
            permissions.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED

            }

        if (missingPermissions.isEmpty()) {

            loadMusic()

        } else {

            permissionLauncher.launch(
                missingPermissions.toTypedArray()
            )
        }
    }

    private fun loadMusic() {

        val songs = mutableListOf<Song>()

        val collection =
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )

        contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->

            val idColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )

            val titleColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )

            val artistColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )

            val albumColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM
                )

            while (cursor.moveToNext()) {

                val id =
                    cursor.getLong(idColumn)

                val title =
                    cursor.getString(titleColumn)
                        ?: "Không tên"

                val artist =
                    cursor.getString(artistColumn)
                        ?: "Nghệ sĩ không rõ"

                val album =
                    cursor.getString(albumColumn)
                        ?: "Album không rõ"

                val uri =
                    MediaStore.Audio.Media
                        .getContentUri(
                            MediaStore.VOLUME_EXTERNAL,
                            id
                        )
                        .toString()

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        uri = uri
                    )
                )
            }
        }

        val mediaItems =
            songs.map { song ->

                MediaItem.Builder()
                    .setMediaId(
                        song.id.toString()
                    )
                    .setUri(song.uri)
                    .setMediaMetadata(

                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .build()

                    )
                    .build()
            }

        controller?.apply {

            setMediaItems(mediaItems)

            prepare()
        }
    }

    override fun onDestroy() {

        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }

        controller = null

        super.onDestroy()
    }
}

@Composable
fun NgocSiMusicApp(
    controllerProvider: () -> MediaController?,
    onScanMusic: () -> Unit
) {

    var searchText by remember {
        mutableStateOf("")
    }

    var playing by remember {
        mutableStateOf(false)
    }

    var currentTitle by remember {
        mutableStateOf("Chưa chọn bài hát")
    }

    var currentArtist by remember {
        mutableStateOf("Thư viện của bạn")
    }

    var songs by remember {
        mutableStateOf<List<MediaItem>>(emptyList())
    }

    val controller =
        controllerProvider()

    DisposableEffect(controller) {

        if (controller == null) {

            onDispose { }

        } else {

            songs =
                (0 until controller.mediaItemCount)
                    .map {
                        controller.getMediaItemAt(it)
                    }

            val listener =
                object : Player.Listener {

                    override fun onIsPlayingChanged(
                        isPlaying: Boolean
                    ) {

                        playing = isPlaying
                    }

                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int
                    ) {

                        currentTitle =
                            mediaItem
                                ?.mediaMetadata
                                ?.title
                                ?.toString()
                                ?: "Chưa chọn bài hát"

                        currentArtist =
                            mediaItem
                                ?.mediaMetadata
                                ?.artist
                                ?.toString()
                                ?: "Thư viện của bạn"
                    }
                }

            controller.addListener(listener)

            onDispose {

                controller.removeListener(listener)

            }
        }
    }

    val filteredSongs =
        songs.filter {

            searchText.isBlank() ||
                    it.mediaMetadata.title
                        ?.toString()
                        ?.contains(
                            searchText,
                            ignoreCase = true
                        ) == true ||
                    it.mediaMetadata.artist
                        ?.toString()
                        ?.contains(
                            searchText,
                            ignoreCase = true
                        ) == true
        }

    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = Black,
                surface = Card,
                primary = Accent,
                onBackground = White,
                onSurface = White
            )
    ) {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Black
        ) {

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(18.dp)
            ) {

                Text(
                    text = "NGỌC SĨ MUSIC",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "REAL MUSIC PLAYER",
                    fontSize = 11.sp,
                    color = Gray
                )

                Spacer(
                    modifier = Modifier.height(14.dp)
                )

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    OutlinedTextField(
                        value = searchText,

                        onValueChange = {
                            searchText = it
                        },

                        modifier =
                            Modifier.weight(1f),

                        placeholder = {
                            Text(
                                "Tìm bài hát...",
                                color = Gray
                            )
                        },

                        singleLine = true,

                        shape =
                            RoundedCornerShape(16.dp),

                        colors =
                            OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor =
                                    Card,

                                focusedContainerColor =
                                    Card,

                                unfocusedBorderColor =
                                    Color.Transparent,

                                focusedBorderColor =
                                    Accent
                            )
                    )

                    Spacer(
                        modifier = Modifier.width(8.dp)
                    )

                    Button(
                        onClick = onScanMusic
                    ) {

                        Text("QUÉT")
                    }
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Card,
                                RoundedCornerShape(22.dp)
                            )
                            .padding(16.dp),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        modifier =
                            Modifier
                                .size(72.dp)
                                .background(
                                    Color(0xFF24242D),
                                    RoundedCornerShape(18.dp)
                                ),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        Text(
                            text = "♫",
                            fontSize = 34.sp,
                            color = Accent
                        )
                    }

                    Spacer(
                        modifier = Modifier.width(14.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {

                        Text(
                            text = currentTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = currentArtist,
                            color = Gray,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceEvenly,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text = "⏮",
                        fontSize = 28.sp,

                        modifier =
                            Modifier.clickable {

                                controller
                                    ?.seekToPreviousMediaItem()
                            }
                    )

                    Button(
                        onClick = {

                            controller?.let {

                                if (it.isPlaying) {

                                    it.pause()

                                } else {

                                    it.play()
                                }
                            }
                        },

                        shape =
                            RoundedCornerShape(50.dp)
                    ) {

                        Text(
                            text =
                                if (playing)
                                    "Ⅱ"
                                else
                                    "▶",

                            fontSize = 25.sp
                        )
                    }

                    Text(
                        text = "⏭",
                        fontSize = 28.sp,

                        modifier =
                            Modifier.clickable {

                                controller
                                    ?.seekToNextMediaItem()
                            }
                    )
                }

                Spacer(
                    modifier = Modifier.height(14.dp)
                )

                Text(
                    text = "THƯ VIỆN NHẠC",
                    color = Gray,
                    fontSize = 12.sp
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                if (filteredSongs.isEmpty()) {

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),

                        horizontalAlignment =
                            Alignment.CenterHorizontally,

                        verticalArrangement =
                            Arrangement.Center
                    ) {

                        Text(
                            text =
                                "Chưa có nhạc",

                            color = Gray
                        )

                        Spacer(
                            modifier =
                                Modifier.height(8.dp)
                        )

                        Text(
                            text =
                                "Nhấn QUÉT để đọc nhạc trong điện thoại.",

                            color = Gray,
                            fontSize = 13.sp
                        )
                    }

                } else {

                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                    ) {

                        items(filteredSongs) { item ->

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {

                                            val index =
                                                songs.indexOf(item)

                                            if (index >= 0) {

                                                controller
                                                    ?.seekToDefaultPosition(
                                                        index
                                                    )

                                                controller?.play()
                                            }
                                        }
                                        .padding(
                                            vertical = 10.dp
                                        ),

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Text(
                                    text = "♫",
                                    color = Accent,
                                    fontSize = 22.sp
                                )

                                Spacer(
                                    modifier =
                                        Modifier.width(12.dp)
                                )

                                Column {

                                    Text(
                                        text =
                                            item.mediaMetadata
                                                .title
                                                ?.toString()
                                                ?: "Không tên",

                                        fontWeight =
                                            FontWeight.SemiBold
                                    )

                                    Text(
                                        text =
                                            item.mediaMetadata
                                                .artist
                                                ?.toString()
                                                ?: "Nghệ sĩ không rõ",

                                        color = Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text =
                        "🎧 Phát nền • Màn hình khóa • Tai nghe",

                    color = Gray,
                    fontSize = 11.sp,

                    modifier =
                        Modifier.padding(
                            top = 8.dp
                        )
                )
            }
        }
    }
}
