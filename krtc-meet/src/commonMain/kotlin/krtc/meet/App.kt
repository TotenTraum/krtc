package krtc.meet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import krtc.client.RemoteParticipant
import krtc.core.MediaStreamTrack

private val Speaking = Color(0xFF34C27A)

@Composable
fun App(session: MeetSession, defaults: JoinSettings) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // Phones: keep clear of the status bar, notch and gesture bar (no insets on desktop).
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val phase = session.phase.collectAsState().value) {
                CallPhase.Idle -> JoinScreen(session, defaults) { session.join(it) }
                CallPhase.Joining -> Centered { Text("Подключение…") }
                is CallPhase.InCall -> CallScreen(session, phase.room)
                is CallPhase.Ended -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (phase.reason == "left") "Вы вышли из звонка" else "Звонок завершён: ${phase.reason ?: "соединение потеряно"}")
                        Button(onClick = session::reset) { Text("Назад") }
                    }
                }
            }
        } }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
fun JoinScreen(session: MeetSession, defaults: JoinSettings, onJoin: (JoinSettings) -> Unit) {
    var server by remember { mutableStateOf(defaults.server) }
    var room by remember { mutableStateOf(defaults.room) }
    var name by remember { mutableStateOf(defaults.name) }
    var testAudio by remember { mutableStateOf(defaults.testAudio) }
    val cameras = remember { session.cameras() }
    var camera by remember { mutableStateOf(defaults.camera) }
    var menu by remember { mutableStateOf(false) }

    Centered {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("krtc meet", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(server, { server = it }, label = { Text("Сервер") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(room, { room = it }, label = { Text("Комната") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedTextField(
                    value = camera.label(), onValueChange = {}, readOnly = true, label = { Text("Видео") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { menu = true })
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    (cameras + listOf(CameraChoice.TestPattern, CameraChoice.None)).forEach { c ->
                        DropdownMenuItem(text = { Text(c.label()) }, onClick = { camera = c; menu = false })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { testAudio = !testAudio }) {
                Checkbox(testAudio, { testAudio = it })
                Text("Тестовый звук вместо микрофона и динамиков")
            }
            if (!testAudio && session.audioDeviceChoice) AudioDevicePickers(session)
            Button(
                onClick = { onJoin(JoinSettings(server.trim(), room.trim(), name.trim(), camera, testAudio)) },
                enabled = server.isNotBlank() && room.isNotBlank() && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Войти") }
        }
    }
}

/** Microphone and speaker choice (platform audio); switching applies at once, also during a call. */
@Composable
fun AudioDevicePickers(session: MeetSession) {
    LaunchedEffect(Unit) { session.refreshAudioDevices() }
    val inputs by session.audioInputs.collectAsState()
    val outputs by session.audioOutputs.collectAsState()
    val input by session.audioInput.collectAsState()
    val output by session.audioOutput.collectAsState()
    DevicePicker("Микрофон", inputs, input, session::selectAudioInput)
    DevicePicker("Динамики", outputs, output, session::selectAudioOutput)
}

@Composable
private fun DevicePicker(label: String, options: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selected?.let { options.getOrNull(it) } ?: "Системное по умолчанию",
            onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled = options.isNotEmpty()) { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}

private fun CameraChoice.label(): String = when (this) {
    CameraChoice.None -> "Без видео"
    CameraChoice.TestPattern -> "Тестовая картинка"
    is CameraChoice.Device -> name
}

/** One tile on the call screen: this user's camera or screen, or a participant's camera or screen share. */
private sealed class TileSpec(val key: String) {
    data object Self : TileSpec(MeetSession.SELF)
    data object SelfScreen : TileSpec(MeetSession.SELF_SCREEN)
    class Camera(val p: RemoteParticipant) : TileSpec(MeetSession.tileKey(p.id, screen = false))
    class Screen(val p: RemoteParticipant) : TileSpec(MeetSession.tileKey(p.id, screen = true))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallScreen(session: MeetSession, room: String) {
    val participants by session.participants.collectAsState()
    val local by session.localVideo.collectAsState()
    val ownScreen by session.screen.collectAsState()
    val sharing by session.sharing.collectAsState()
    val chatOpen by session.chatOpen.collectAsState()
    val unread by session.unread.collectAsState()
    val notice by session.notice.collectAsState()
    val reconnecting by session.reconnecting.collectAsState()
    val layout by session.layout.collectAsState()
    val pinned by session.pinned.collectAsState()
    val speaker by session.activeSpeaker.collectAsState()
    val micOn by session.micOn.collectAsState()
    val cameraOn by session.cameraOn.collectAsState()

    val tiles = buildList {
        add(TileSpec.Self)
        if (ownScreen != null) add(TileSpec.SelfScreen)
        participants.forEach { add(TileSpec.Camera(it)) }
        participants.filter { it.id in sharing }.forEach { add(TileSpec.Screen(it)) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$room · ${participants.size + 1} в звонке", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ConnectionBadge(session)
            TextButton(onClick = { session.setLayout(if (layout == Layout.GRID) Layout.SPEAKER else Layout.GRID) }) {
                Text(if (layout == Layout.GRID) "Докладчик" else "Сетка")
            }
        }
        reconnecting?.let { attempt ->
            Banner("Нет связи с сервером, переподключение… (попытка $attempt)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        notice?.let { n -> Banner(n, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, session::dismissNotice) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!chatOpen || wide) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val tile: @Composable (TileSpec, Modifier) -> Unit = { spec, m -> TileFor(session, spec, pinned == spec.key, m) }
                        if (layout == Layout.GRID) {
                            LazyVerticalGrid(GridCells.Adaptive(minSize = 300.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(tiles, key = { it.key }) { spec -> tile(spec, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                            }
                        } else {
                            // The large tile: what the user pinned, else a screen share, else whoever spoke last.
                            val main = tiles.firstOrNull { it.key == pinned }
                                ?: tiles.firstOrNull { it is TileSpec.Screen }
                                ?: tiles.firstOrNull { it is TileSpec.Camera && it.p.id == speaker }
                                ?: tiles.firstOrNull { it is TileSpec.Camera }
                                ?: tiles.first()
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                tile(main, Modifier.weight(1f).fillMaxWidth())
                                val others = tiles.filter { it !== main }
                                if (others.isNotEmpty()) {
                                    LazyRow(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(others, key = { it.key }) { spec -> tile(spec, Modifier.fillMaxHeight().aspectRatio(16f / 9f)) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (chatOpen) ChatPanel(session, if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(12.dp))
        // Wraps onto more lines on narrow (phone) screens.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = session::toggleMic) { Text(if (micOn) "Выключить микрофон" else "Включить микрофон") }
            FilledTonalButton(onClick = session::toggleCamera, enabled = local != null) { Text(if (cameraOn) "Выключить камеру" else "Включить камеру") }
            ScreenShareButton(session, sharing = ownScreen != null)
            FilledTonalButton(onClick = { session.setChatOpen(!chatOpen) }) { Text(if (unread > 0 && !chatOpen) "Чат ($unread)" else "Чат") }
            SoundMenu(session)
            Button(onClick = session::leave, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Выйти") }
        }
    }
}

@Composable
private fun Banner(text: String, background: Color, foreground: Color, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = foreground)
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("OK") }
    }
}

private fun LinkQuality.label() = when (this) {
    LinkQuality.GOOD -> "хорошая"
    LinkQuality.FAIR -> "средняя"
    LinkQuality.POOR -> "плохая"
}

private fun LinkQuality.color() = when (this) {
    LinkQuality.GOOD -> Speaking
    LinkQuality.FAIR -> Color(0xFFE0B03A)
    LinkQuality.POOR -> Color(0xFFE5534B)
}

/** The call's own connection: quality, round trip and loss, from getStats. */
@Composable
private fun ConnectionBadge(session: MeetSession) {
    val info = session.connection.collectAsState().value ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(info.quality.color()))
        val details = listOfNotNull(info.rttMillis?.let { "$it мс" }, if (info.lossPercent >= 0.5) "потери ${info.lossPercent.roundToInt()} %" else null)
        Text("Связь ${info.quality.label()}" + if (details.isEmpty()) "" else " · " + details.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TileFor(session: MeetSession, spec: TileSpec, pinned: Boolean, modifier: Modifier) {
    val pin = { session.togglePin(spec.key) }
    when (spec) {
        TileSpec.Self -> {
            val local by session.localVideo.collectAsState()
            val cameraOn by session.cameraOn.collectAsState()
            val micOn by session.micOn.collectAsState()
            val speaking by session.selfSpeaking.collectAsState()
            Tile(local.takeIf { cameraOn }, "${session.settings?.name ?: ""} (вы)", speaking && micOn, modifier, mirror = true, muted = !micOn, pinned = pinned, onClick = pin)
        }
        TileSpec.SelfScreen -> Tile(session.screen.collectAsState().value, "Ваш экран", speaking = false, modifier, fit = true, pinned = pinned, onClick = pin)
        is TileSpec.Camera -> RemoteTile(session, spec.p, pinned, modifier, pin)
        is TileSpec.Screen -> RemoteScreenTile(session, spec.p, pinned, modifier, pin)
    }
}

/** Starts a screen share (choice of display or window, with or without sound) or stops it. */
@Composable
private fun ScreenShareButton(session: MeetSession, sharing: Boolean) {
    val fps by session.screenFps.collectAsState()
    if (sharing) {
        FilledTonalButton(onClick = session::stopScreen) { Text("Остановить показ · $fps к/с") }
        return
    }
    Box {
        var open by remember { mutableStateOf(false) }
        var withAudio by remember { mutableStateOf(false) }
        var choices by remember { mutableStateOf(emptyList<ScreenChoice>()) }
        FilledTonalButton(onClick = { choices = session.screens(); open = true }) { Text("Показать экран") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withAudio = !withAudio }.padding(end = 12.dp)) {
                Checkbox(withAudio, { withAudio = it })
                Text("Со звуком")
            }
            // Frame rate: 15 is enough for slides and code, 30 and 60 for video and scrolling.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Кадров в секунду", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 6.dp))
                MeetSession.SCREEN_FPS_CHOICES.forEach { choice ->
                    FilterChip(selected = fps == choice, onClick = { session.setScreenFps(choice) }, label = { Text("$choice") })
                }
            }
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                    open = false
                    session.startScreen(c, withAudio)
                })
            }
        }
    }
}

/** Devices (where they can be chosen) and microphone processing. */
@Composable
private fun SoundMenu(session: MeetSession) {
    Box {
        var open by remember { mutableStateOf(false) }
        FilledTonalButton(onClick = { open = true }) { Text("Звук") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Column(Modifier.width(360.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (session.settings?.testAudio == false && session.audioDeviceChoice) AudioDevicePickers(session)
                val processing by session.audioProcessing.collectAsState()
                Text("Микрофон", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                CheckRow("Адаптивная громкость (автоусиление)", processing.autoGainControl) { session.setAudioProcessing(processing.copy(autoGainControl = it)) }
                CheckRow("Шумоподавление", processing.noiseSuppression) { session.setAudioProcessing(processing.copy(noiseSuppression = it)) }
                CheckRow("Эхоподавление", processing.echoCancellation) { session.setAudioProcessing(processing.copy(echoCancellation = it)) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun ChatPanel(session: MeetSession, modifier: Modifier) {
    val messages by session.chat.collectAsState()
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    val send = { if (session.sendChat(draft)) draft = "" }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Чат", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { session.setChatOpen(false) }) { Text("Закрыть") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) item { Text("Сообщений пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(messages) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Text(if (m.mine) "Вы" else m.from ?: "Сервер", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        m.text,
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                draft, { draft = it }, placeholder = { Text("Сообщение") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = send, enabled = draft.isNotBlank()) { Text("➤") }
        }
    }
}

@Composable
private fun RemoteTile(session: MeetSession, p: RemoteParticipant, pinned: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val video by p.video.collectAsState()
    val speaking by p.speaking.collectAsState()
    val audioMuted by p.audioMuted.collectAsState()
    val videoMuted by p.videoMuted.collectAsState()
    val quality = session.connection.collectAsState().value?.peers?.get(p.id)
    // The SFU sends the simulcast layer that fills this tile; none needed while it is off screen.
    DisposableEffect(p) { onDispose { session.setTileHeight(p, 0) } }
    Tile(
        video.takeIf { !videoMuted }, p.name, speaking && !audioMuted,
        modifier.onSizeChanged { session.setTileHeight(p, it.height) },
        muted = audioMuted, pinned = pinned, onClick = onClick,
        warning = quality?.takeIf { it != LinkQuality.GOOD }?.let { "связь ${it.label()}" },
    ) {
        VolumeMenu(session, p, screen = false, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * A participant's screen share: the picture when subscribed, otherwise an offer to connect (nothing is received
 * meanwhile).
 */
@Composable
private fun RemoteScreenTile(session: MeetSession, p: RemoteParticipant, pinned: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val screen by p.screen.collectAsState()
    val subscribed by p.screenSubscribed.collectAsState()
    val sound by p.screenAudio.collectAsState()
    Tile(screen.takeIf { subscribed }, "${p.name} — экран", speaking = false, modifier, fit = true, placeholder = !subscribed, pinned = pinned, onClick = onClick) {
        if (subscribed) {
            Row(Modifier.align(Alignment.TopEnd)) {
                if (sound != null) VolumeMenu(session, p, screen = true)
                OverlayButton("Отключиться") { session.setScreenSubscribed(p, false) }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.name} показывает экран", color = Color.White)
                Button(onClick = { session.setScreenSubscribed(p, true) }) { Text("Подключиться") }
            }
        }
    }
}

/** Volume of a participant's voice (or its screen's sound), 0–300 %. */
@Composable
private fun VolumeMenu(session: MeetSession, p: RemoteParticipant, screen: Boolean, modifier: Modifier = Modifier) {
    val volumes by session.volumes.collectAsState()
    val volume = volumes[MeetSession.volumeKey(p.id, screen)] ?: 1f
    Box(modifier) {
        var open by remember { mutableStateOf(false) }
        OverlayButton(if (volume == 0f) "Без звука" else "Громкость ${(volume * 100).roundToInt()} %") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (screen) "Звук экрана: ${p.name}" else "Голос: ${p.name}", style = MaterialTheme.typography.labelLarge)
                Slider(volume, { session.setVolume(p, it, screen) }, valueRange = 0f..MeetSession.MAX_VOLUME)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { session.setVolume(p, 0f, screen) }) { Text("Выключить") }
                    TextButton(onClick = { session.setVolume(p, 1f, screen) }) { Text("100 %") }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Tile(
    track: MediaStreamTrack?,
    label: String,
    speaking: Boolean,
    modifier: Modifier,
    mirror: Boolean = false,
    muted: Boolean = false,
    fit: Boolean = false,
    placeholder: Boolean = false,
    pinned: Boolean = false,
    warning: String? = null,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape).background(Color(0xFF1E1F24))
            .border(BorderStroke(3.dp, if (speaking) Speaking else Color.Transparent), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        VideoView(track, Modifier.fillMaxSize(), mirror = mirror, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        if (track == null && !placeholder) Text(label.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            warning?.let { Text(it, color = Color(0xFFFFC857), style = MaterialTheme.typography.labelSmall) }
            Text(
                label + (if (muted) " · микрофон выключен" else "") + (if (pinned) " · закреплено" else ""),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        overlay()
    }
}
