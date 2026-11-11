package krtc.meet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallScreen(session: MeetSession, room: String) {
    val participants by session.participants.collectAsState()
    val local by session.localVideo.collectAsState()
    val micOn by session.micOn.collectAsState()
    val cameraOn by session.cameraOn.collectAsState()
    val selfSpeaking by session.selfSpeaking.collectAsState()
    val ownScreen by session.screen.collectAsState()
    val chatOpen by session.chatOpen.collectAsState()
    val unread by session.unread.collectAsState()
    val notice by session.notice.collectAsState()
    val name = session.settings?.name ?: ""

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("$room · ${participants.size + 1} в звонке", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        notice?.let { n ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = session::dismissNotice) { Text("OK") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!chatOpen || wide) {
                    LazyVerticalGrid(GridCells.Adaptive(minSize = 300.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "self") { Tile(local.takeIf { cameraOn }, "$name (вы)", selfSpeaking && micOn, mirror = true, muted = !micOn) }
                        ownScreen?.let { item(key = "self/screen") { Tile(it, "Ваш экран", speaking = false, fit = true) } }
                        items(participants, key = { it.id }) { p -> RemoteTile(session, p) }
                        items(participants, key = { it.id + "/screen" }) { p -> RemoteScreenTile(session, p) }
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

/** Starts a screen share (choice of display or window, with or without sound) or stops it. */
@Composable
private fun ScreenShareButton(session: MeetSession, sharing: Boolean) {
    if (sharing) {
        FilledTonalButton(onClick = session::stopScreen) { Text("Остановить показ") }
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
private fun RemoteTile(session: MeetSession, p: RemoteParticipant) {
    val video by p.video.collectAsState()
    val speaking by p.speaking.collectAsState()
    val audioMuted by p.audioMuted.collectAsState()
    val videoMuted by p.videoMuted.collectAsState()
    Tile(video.takeIf { !videoMuted }, p.name, speaking && !audioMuted, muted = audioMuted) {
        VolumeMenu(session, p, screen = false, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * A participant's screen share, while there is one: the picture when subscribed, otherwise an offer to connect
 * (nothing is received meanwhile).
 */
@Composable
private fun RemoteScreenTile(session: MeetSession, p: RemoteParticipant) {
    val screen by p.screen.collectAsState()
    val subscribed by p.screenSubscribed.collectAsState()
    val sound by p.screenAudio.collectAsState()
    val track = screen ?: return
    Tile(track.takeIf { subscribed }, "${p.name} — экран", speaking = false, fit = true, placeholder = !subscribed) {
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
    mirror: Boolean = false,
    muted: Boolean = false,
    fit: Boolean = false,
    placeholder: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color(0xFF1E1F24))
            .border(BorderStroke(3.dp, if (speaking) Speaking else Color.Transparent), shape),
    ) {
        VideoView(track, Modifier.fillMaxSize(), mirror = mirror, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        if (track == null && !placeholder) Text(label.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.Center))
        Text(
            label + if (muted) " · микрофон выключен" else "",
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
        overlay()
    }
}
