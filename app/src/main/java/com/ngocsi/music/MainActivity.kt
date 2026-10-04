
    @Composable
    private fun PlaylistDetailDialog(playlist: MusicPlaylist) {
        val playlistSongs = playlist.songUris.mapNotNull { uri ->
            songs.firstOrNull { it.uri.toString() == uri }
        }

        Dialog(onDismissRequest = { playlistDetailId = null }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.95f)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(playlist.songUris.size.toString() + " bài", color = Color(0xFF888894), fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = { playPlaylist(playlist) },
                                enabled = playlistSongs.isNotEmpty()
                            ) { Text("▶ Phát") }
                            TextButton(
                                onClick = { addQueueToPlaylist(playlist) },
                                enabled = queueSongs.isNotEmpty()
                            ) { Text("＋ HÀNG ĐỢI") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (playlistSongs.isEmpty()) {
                        Text(
                            "Playlist chưa có bài khả dụng. Hãy thêm nhạc bằng nút ▣ trong thư viện.",
                            color = Color(0xFF9999A5),
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(playlistSongs, key = { _, song -> song.uri.toString() }) { index, song ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF181922))
                                        .clickable { playPlaylistFromSong(playlist, index) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, softWrap = true, lineHeight = 18.sp)
                                        Text(song.artist, color = Color(0xFF888894), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = { playPlaylistFromSong(playlist, index) }) { Text("▶") }
                                    TextButton(
                                        onClick = {
                                            playlistStore.removeSong(playlist.id, song.uri.toString())
                                            refreshPlaylists()
                                        }
                                    ) { Text("XÓA", color = Color(0xFFFF8A9A)) }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { playlistDetailId = null }) { Text("Đóng") }
                }
            }
        }
    }

    @Composable
    private fun PlaylistPickerDialog(song: Song) {
        Dialog(onDismissRequest = { playlistTargetSongUri = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF101117),