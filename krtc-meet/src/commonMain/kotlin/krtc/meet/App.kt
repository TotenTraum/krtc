package krtc.meet

import androidx.compose.foundation.BorderStroke
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
    val name = session.settings?.name ?: ""

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("$room · ${participants.size + 1} в звонке", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(GridCells.Adaptive(minSize = 300.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "self") { Tile(local.takeIf { cameraOn }, "$name (вы)", selfSpeaking && micOn, mirror = true, muted = !micOn) }
            items(participants, key = { it.id }) { p -> RemoteTile(p) }
            items(participants, key = { it.id + "/screen" }) { p -> RemoteScreenTile(p) }
        }
        Spacer(Modifier.height(12.dp))
        // Wraps onto a second line on narrow (phone) screens.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = session::toggleMic) { Text(if (micOn) "Выключить микрофон" else "Включить микрофон") }
            FilledTonalButton(onClick = session::toggleCamera, enabled = local != null) { Text(if (cameraOn) "Выключить камеру" else "Включить камеру") }
            if (session.settings?.testAudio == false && session.audioDeviceChoice) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    FilledTonalButton(onClick = { open = true }) { Text("Звук") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        Column(Modifier.width(360.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { AudioDevicePickers(session) }
                    }
                }
            }
            Button(onClick = session::leave, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Выйти") }
        }
    }
}

@Composable
private fun RemoteTile(p: RemoteParticipant) {
    val video by p.video.collectAsState()
    val speaking by p.speaking.collectAsState()
    Tile(video, p.name, speaking)
}

/** A participant's screen share, while there is one (nothing otherwise). */
@Composable
private fun RemoteScreenTile(p: RemoteParticipant) {
    val screen by p.screen.collectAsState()
    screen?.let { Tile(it, "${p.name} — экран", speaking = false, fit = true) }
}

@Composable
private fun Tile(track: MediaStreamTrack?, label: String, speaking: Boolean, mirror: Boolean = false, muted: Boolean = false, fit: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
            .border(BorderStroke(3.dp, if (speaking) Speaking else Color.Transparent), shape),
    ) {
        VideoView(track, Modifier.fillMaxSize(), mirror = mirror, contentScale = if (fit) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
        if (track == null) Text(label.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.Center))
        Text(
            label + if (muted) " · микрофон выключен" else "",
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}
