                    singleLine = true,
                    label = { Text("Tên playlist") },
                    placeholder = { Text("Ví dụ: Nhạc vàng yêu thích") },
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = { Button(onClick = ::createPlaylist) { Text("TẠO") } },
            dismissButton = {
                TextButton(onClick = {
                    playlistTargetSongUri = null
                    showCreatePlaylist = false
                }) { Text("HỦY") }
            }
        )
    }

    @Composable
    private fun SongRow(song: Song, index: Int, selected: Boolean) {
        val shownDuration = if (selected && duration > 0L) duration else song.duration
        var menuExpanded by remember(song.uri.toString()) { mutableStateOf(false) }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) Color(0xFF28203D) else Color(0xFF14161D))
                .clickable { play(index) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (selected) Color(0xFF7657D8) else Color(0xFF222530)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (selected) "▶" else String.format("%02d", index + 1),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(9.dp))
            SongArtwork(song, Modifier.size(54.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        song.artist.ifBlank { "Nghệ sĩ chưa xác định" },
                        color = Color(0xFF989AA8),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SourceBadge(song.source)
                }
                if (libraryView == "Thư mục" && song.folder.isNotBlank()) {
                    Text(
                        song.folder,
                        color = Color(0xFF70727E),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(5.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTime(shownDuration), color = Color(0xFF858895), fontSize = 9.sp)
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Text("⋮", color = Color(0xFFADB2BF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Phát tiếp") },
                            onClick = {
                                menuExpanded = false
                                playNext(song)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Thêm vào hàng đợi") },
                            onClick = {
                                menuExpanded = false
                                addToQueue(song)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Thêm vào playlist") },
                            onClick = {
                                menuExpanded = false
                                playlistTargetSongUri = song.uri.toString()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(if (favorites[song.id] == true) "Bỏ khỏi yêu thích" else "Thêm vào yêu thích")
                            },
                            onClick = {
                                menuExpanded = false
                                toggleFavorite(song)
                            }
                        )
                    }
                }
            }
        }
    }


    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = max(0L, milliseconds) / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}