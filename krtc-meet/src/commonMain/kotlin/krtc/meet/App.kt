package krtc.meet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.Image
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import krtc.native.NativeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.RadioButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val Speaking = MeetColors.Green

@Composable
fun App(session: MeetSession, defaults: JoinSettings) {
    MaterialTheme(colorScheme = MeetColors.scheme) {
        // Phones: keep clear of the status bar, notch and gesture bar (no insets on desktop).
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (val phase = session.phase.collectAsState().value) {
                CallPhase.Idle -> JoinScreen(session, defaults) { session.join(it) }
                CallPhase.Joining -> Centered { Text("Подключение…") }
                is CallPhase.InCall -> CallScreen(session, phase.room)
                is CallPhase.Ended -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (phase.reason == "left") "Вы вышли из звонка" else "Звонок завершён: ${phase.reason ?: "соединение потеряно"}")
                        if (phase.reason != "left") logLocation()?.let {
                            Text("Журнал: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("krtc meet", style = MaterialTheme.typography.headlineSmall)
                Text(BuildInfo.VERSION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            LaunchedEffect(Unit) { session.checkUpdates() }
            session.update.collectAsState().value?.let { u ->
                val uri = LocalUriHandler.current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Доступна версия ${u.version}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    TextButton(onClick = { runCatching { uri.openUri(u.url) } }) { Text("Скачать", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
            OutlinedTextField(
                server, { server = it }, label = { Text("Сервер") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("адрес:порт, например 192.168.1.10:8080 или wss://meet.example.com") },
            )
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
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = { if (i == selected) Text("Выбрано", style = MaterialTheme.typography.labelSmall) },
                    onClick = { onSelect(i); open = false },
                )
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
    val ownScreen by session.screen.collectAsState()
    val sharing by session.sharing.collectAsState()
    val chatOpen by session.chatOpen.collectAsState()
    val notice by session.notice.collectAsState()
    val reconnecting by session.reconnecting.collectAsState()
    val layout by session.layout.collectAsState()
    val pinned by session.pinned.collectAsState()
    val speaker by session.activeSpeaker.collectAsState()
    val expanded by session.expanded.collectAsState()
    var membersOpen by remember { mutableStateOf<Boolean?>(null) } // null: shown on wide windows
    var typing by remember { mutableStateOf(false) }

    val tiles = buildList {
        add(TileSpec.Self)
        if (ownScreen != null) add(TileSpec.SelfScreen)
        participants.forEach { add(TileSpec.Camera(it)) }
        participants.filter { it.id in sharing }.forEach { add(TileSpec.Screen(it)) }
    }

    // Keyboard shortcuts work wherever the focus is inside the call screen.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { handleKey(session, it, typing) }

    // A tile expanded to the whole window (a stream being watched): nothing else on screen.
    tiles.firstOrNull { it.key == expanded }?.let { spec ->
        Box(Modifier.fillMaxSize().background(Color.Black).then(keys)) { TileFor(session, spec, pinned = false, expanded = true, Modifier.fillMaxSize()) }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MeetColors.Background).then(keys)) {
        val wide = maxWidth >= 720.dp
        val members = membersOpen ?: wide
        Column(Modifier.fillMaxSize()) {
            CallTopBar(session, room, participants.size + 1, layout, members) { membersOpen = !members }
            Column(Modifier.padding(horizontal = 12.dp)) {
                reconnecting?.let { attempt ->
                    Banner("Нет связи с сервером, переподключение… (попытка $attempt)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                notice?.let { n -> Banner(n, MeetColors.Raised, MeetColors.Text, session::dismissNotice) }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // On a phone the list and the chat take the place of the tiles while open.
                if (members && (wide || !chatOpen)) MembersPanel(session, participants, sharing, pinned, if (wide) Modifier.width(240.dp).fillMaxHeight() else Modifier.fillMaxSize())
                if ((!chatOpen || wide) && (wide || !members)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val tile: @Composable (TileSpec, Modifier, Boolean) -> Unit = { spec, m, compact -> TileFor(session, spec, pinned == spec.key, expanded = false, m, compact) }
                        if (layout == Layout.GRID) {
                            FitGrid(tiles, Modifier.fillMaxSize(), key = { it.key }) { spec, m -> tile(spec, m, false) }
                        } else {
                            // The large tile: what the user pinned, else a screen share, else whoever spoke last.
                            val main = tiles.firstOrNull { it.key == pinned }
                                ?: tiles.firstOrNull { it is TileSpec.Screen }
                                ?: tiles.firstOrNull { it is TileSpec.Camera && it.p.id == speaker }
                                ?: tiles.firstOrNull { it is TileSpec.Camera }
                                ?: tiles.first()
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                tile(main, Modifier.weight(1f).fillMaxWidth(), false)
                                val others = tiles.filter { it !== main }
                                if (others.isNotEmpty()) {
                                    LazyRow(Modifier.fillMaxWidth().height(if (wide) 112.dp else 84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                        items(others, key = { it.key }) { spec -> tile(spec, Modifier.fillMaxHeight().aspectRatio(16f / 9f), true) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (chatOpen) ChatPanel(session, if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) { typing = it }
            }
            ControlBar(session, compact = !wide)
        }
    }
}

/** Room name and size, the connection, and the panel and layout switches. */
@Composable
private fun CallTopBar(session: MeetSession, room: String, count: Int, layout: Layout, membersShown: Boolean, onMembers: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(room, style = MaterialTheme.typography.titleMedium, color = MeetColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MeetColors.TextMuted, maxLines = 1)
        }
        ConnectionBadge(session)
        ControlButton(MeetIcons.People, if (membersShown) "Скрыть участников" else "Участники", onMembers, tone = if (membersShown) Tone.ACTIVE else Tone.GHOST, size = 36.dp)
        ControlButton(
            if (layout == Layout.GRID) MeetIcons.Speaker else MeetIcons.Grid,
            if (layout == Layout.GRID) "Режим докладчика" else "Сетка",
            { session.setLayout(if (layout == Layout.GRID) Layout.SPEAKER else Layout.GRID) }, tone = Tone.GHOST, size = 36.dp,
        )
    }
}

/** Microphone, sound, camera, screen, chat, settings and leave: round icon buttons, hints on hover. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlBar(session: MeetSession, compact: Boolean) {
    val local by session.localVideo.collectAsState()
    val ownScreen by session.screen.collectAsState()
    val chatOpen by session.chatOpen.collectAsState()
    val unread by session.unread.collectAsState()
    val micOn by session.micOn.collectAsState()
    val cameraOn by session.cameraOn.collectAsState()
    val deafened by session.deafened.collectAsState()
    val input by session.inputSettings.collectAsState()
    val size = if (compact) 42.dp else 48.dp
    val shortcut = if (isDesktop) " (Ctrl/⌘+Shift+%s)" else ""
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (input.mode == InputMode.PUSH_TO_TALK && micOn && !deafened) PushToTalkButton(session, size)
        val talking = micOn && !deafened
        ControlButton(
            if (talking) MeetIcons.Mic else MeetIcons.MicOff,
            (if (talking) "Выключить микрофон" else "Включить микрофон") + shortcut.replace("%s", "M"),
            session::toggleMic, tone = if (talking) Tone.NORMAL else Tone.OFF, size = size,
        )
        ControlButton(
            if (deafened) MeetIcons.HeadsetOff else MeetIcons.Headset,
            (if (deafened) "Включить звук" else "Заглушить всё") + shortcut.replace("%s", "D"),
            session::toggleDeafen, tone = if (deafened) Tone.OFF else Tone.NORMAL, size = size,
        )
        ControlButton(
            if (cameraOn && local != null) MeetIcons.Videocam else MeetIcons.VideocamOff,
            when {
                local == null -> "Камера не выбрана"
                cameraOn -> "Выключить камеру"
                else -> "Включить камеру"
            },
            session::toggleCamera, tone = if (cameraOn || local == null) Tone.NORMAL else Tone.OFF, enabled = local != null, size = size,
        )
        ScreenShareButton(session, sharing = ownScreen != null, size)
        ControlButton(MeetIcons.Chat, if (chatOpen) "Скрыть чат" else "Чат", { session.setChatOpen(!chatOpen) }, tone = if (chatOpen) Tone.ACTIVE else Tone.NORMAL, badge = if (chatOpen) 0 else unread, size = size)
        SoundMenu(session, size)
        if (!compact) Spacer(Modifier.width(8.dp))
        ControlButton(MeetIcons.CallEnd, "Выйти из звонка", session::leave, tone = Tone.DANGER, size = size, wide = true)
    }
}

/**
 * Shortcuts: Ctrl/Cmd+Shift+M mutes, Ctrl/Cmd+Shift+D deafens, Esc leaves the expanded tile, and ` (the key under Esc)
 * is push-to-talk while that mode is on and no text is being typed. They work while the window has the focus.
 */
private fun handleKey(session: MeetSession, e: KeyEvent, typing: Boolean): Boolean {
    val down = e.type == KeyEventType.KeyDown
    val shortcut = (e.isCtrlPressed || e.isMetaPressed) && e.isShiftPressed
    return when {
        down && shortcut && e.key == Key.M -> { session.toggleMic(); true }
        down && shortcut && e.key == Key.D -> { session.toggleDeafen(); true }
        down && e.key == Key.Escape && session.expanded.value != null -> { session.setExpanded(null); true }
        e.key == Key.Grave && !typing && session.inputSettings.value.mode == InputMode.PUSH_TO_TALK -> {
            session.setPushToTalk(down)
            true
        }
        else -> false
    }
}

/** Push-to-talk for touch screens (and the mouse): transmits while pressed. */
@Composable
private fun PushToTalkButton(session: MeetSession, height: Dp) {
    val transmitting by session.transmitting.collectAsState()
    Box(
        Modifier.height(height).clip(RoundedCornerShape(height / 2))
            .background(if (transmitting) Speaking else MeetColors.Raised)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    session.setPushToTalk(true)
                    tryAwaitRelease()
                    session.setPushToTalk(false)
                })
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (transmitting) "Говорите…" else if (isDesktop) "Удерживайте ` или здесь" else "Удерживайте, чтобы говорить",
            color = if (transmitting) Color.Black else MeetColors.Text, style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Who is in the call: speaking, muted, deafened, on air. A click pins a person; the ⋮ (on hover) holds the volumes,
 * watching their stream and pinning.
 */
@Composable
private fun MembersPanel(session: MeetSession, participants: List<RemoteParticipant>, sharing: Set<String>, pinned: String?, modifier: Modifier) {
    val micOn by session.micOn.collectAsState()
    val deafened by session.deafened.collectAsState()
    val transmitting by session.transmitting.collectAsState()
    val selfSpeaking by session.selfSpeaking.collectAsState()
    val ownScreen by session.screen.collectAsState()
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(MeetColors.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "В ЗВОНКЕ — ${participants.size + 1}", style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "self") {
                MemberRow(
                    name = session.settings?.name ?: "", self = true, speaking = selfSpeaking && transmitting,
                    muted = !micOn, deafened = deafened, live = ownScreen != null, onClick = { session.togglePin(MeetSession.SELF) },
                )
            }
            items(participants, key = { it.id }) { p ->
                val speaking by p.speaking.collectAsState()
                val audioMuted by p.audioMuted.collectAsState()
                val deaf by p.deafened.collectAsState()
                val watching by p.screenSubscribed.collectAsState()
                val live = p.id in sharing
                val key = MeetSession.tileKey(p.id, screen = false)
                MemberRow(
                    name = p.name, self = false, speaking = speaking && !audioMuted, muted = audioMuted, deafened = deaf, live = live,
                    onClick = { session.togglePin(key) },
                ) { close ->
                    DropdownMenuItem(text = { Text(if (pinned == key) "Открепить" else "Закрепить") }, leadingIcon = { Icon(MeetIcons.Pin, null, Modifier.size(18.dp)) }, onClick = { session.togglePin(key); close() })
                    if (live) DropdownMenuItem(
                        text = { Text(if (watching) "Не смотреть стрим" else "Смотреть стрим") },
                        leadingIcon = { Icon(if (watching) MeetIcons.EyeOff else MeetIcons.ScreenShare, null, Modifier.size(18.dp)) },
                        onClick = { session.setScreenSubscribed(p, !watching); close() },
                    )
                    VolumeControls(session, p, screen = false)
                    if (live) VolumeControls(session, p, screen = true, hasSound = p.screenAudio.collectAsState().value != null)
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    name: String, self: Boolean, speaking: Boolean, muted: Boolean, deafened: Boolean, live: Boolean, onClick: () -> Unit,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hovered || open) MeetColors.Raised else Color.Transparent)
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The avatar, ringed while speaking.
        Box(Modifier.size(32.dp).border(BorderStroke(2.dp, if (speaking) Speaking else Color.Transparent), CircleShape).padding(3.dp)) { Avatar(name, 26.dp) }
        Text(
            if (self) "$name (вы)" else name, color = if (speaking) Color.White else MeetColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        if (live) LiveBadge()
        when {
            deafened -> Icon(MeetIcons.HeadsetOff, "Заглушён", Modifier.size(16.dp), tint = MeetColors.Red)
            muted -> Icon(MeetIcons.MicOff, "Микрофон выключен", Modifier.size(16.dp), tint = MeetColors.Red)
        }
        if (menu != null && (hovered || open || !isDesktop)) {
            Box {
                Icon(
                    MeetIcons.More, "Действия", Modifier.size(20.dp).clip(CircleShape).clickable { open = true },
                    tint = MeetColors.TextMuted,
                )
                DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Text(
        "В ЭФИРЕ", color = Color.White, maxLines = 1, softWrap = false, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Live).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Banner(text: String, background: Color, foreground: Color, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = foreground, style = MaterialTheme.typography.bodyMedium)
        if (onDismiss != null) ControlButton(MeetIcons.Close, "Скрыть", onDismiss, tone = Tone.GHOST, size = 28.dp)
    }
}

private fun LinkQuality.label() = when (this) {
    LinkQuality.GOOD -> "хорошая"
    LinkQuality.FAIR -> "средняя"
    LinkQuality.POOR -> "плохая"
}

private fun LinkQuality.color() = when (this) {
    LinkQuality.GOOD -> Speaking
    LinkQuality.FAIR -> MeetColors.Yellow
    LinkQuality.POOR -> MeetColors.Red
}

/** The call's own connection: a dot and the round trip; quality and loss in the hint. */
@Composable
private fun ConnectionBadge(session: MeetSession) {
    val info = session.connection.collectAsState().value ?: return
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            Modifier.hoverable(interaction).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(info.quality.color()))
            Text(info.rttMillis?.let { "$it мс" } ?: "связь", style = MaterialTheme.typography.labelMedium, color = MeetColors.TextMuted)
        }
        val details = listOfNotNull(info.rttMillis?.let { "задержка $it мс" }, "потери ${info.lossPercent.roundToInt()} %")
        Hint("Связь ${info.quality.label()} · " + details.joinToString(" · "), hovered)
    }
}

@Composable
private fun TileFor(session: MeetSession, spec: TileSpec, pinned: Boolean, expanded: Boolean, modifier: Modifier, compact: Boolean = false) {
    val pin = { session.togglePin(spec.key) }
    // Small tiles (the strip under the large one) have no room for it: click to make them large first.
    val expand: (() -> Unit)? = if (compact) null else ({ session.setExpanded(if (expanded) null else spec.key) })
    when (spec) {
        TileSpec.Self -> {
            val local by session.localVideo.collectAsState()
            val cameraOn by session.cameraOn.collectAsState()
            val micOn by session.micOn.collectAsState()
            val speaking by session.selfSpeaking.collectAsState()
            val transmitting by session.transmitting.collectAsState()
            val deafened by session.deafened.collectAsState()
            Tile(
                local.takeIf { cameraOn }, session.settings?.name ?: "", speaking && transmitting, modifier, mirror = true, suffix = " (вы)",
                muted = !micOn, deafened = deafened, pinned = pinned, expanded = expanded, onExpand = expand, onClick = pin, compact = compact,
            )
        }
        TileSpec.SelfScreen -> Tile(
            session.screen.collectAsState().value, "Ваш экран", speaking = false, modifier, fit = true, pinned = pinned,
            expanded = expanded, onExpand = expand, onClick = pin, compact = compact,
        )
        is TileSpec.Camera -> RemoteTile(session, spec.p, pinned, expanded, expand, modifier, compact, pin)
        is TileSpec.Screen -> RemoteScreenTile(session, spec.p, pinned, expanded, expand, modifier, compact, pin)
    }
}

/** Starts a screen share in the picker, or while sharing offers to change what is shared or to stop. */
@Composable
private fun ScreenShareButton(session: MeetSession, sharing: Boolean, size: Dp) {
    val picker by session.screenPicker.collectAsState()
    if (picker) ScreenPicker(session, live = sharing) { session.setScreenPicker(false) }
    if (!sharing) {
        ControlButton(MeetIcons.ScreenShare, "Показать экран", { session.setScreenPicker(true) }, size = size)
        return
    }
    val fps by session.screenFps.collectAsState()
    val send by session.screenSend.collectAsState()
    Box {
        var open by remember { mutableStateOf(false) }
        // What actually goes out (size, frame rate, bitrate, what holds it back), once the first stats are in.
        val hint = send?.let { "В эфире · ${it.describe()}" } ?: "В эфире · $fps к/с"
        ControlButton(MeetIcons.ScreenShare, hint, { open = true }, tone = Tone.LIVE, size = size)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Сменить окно или экран") }, leadingIcon = { Icon(MeetIcons.ScreenShare, null, Modifier.size(18.dp)) }, onClick = { open = false; session.setScreenPicker(true) })
            DropdownMenuItem(text = { Text("Остановить трансляцию", color = MeetColors.Red) }, leadingIcon = { Icon(MeetIcons.Close, null, Modifier.size(18.dp), tint = MeetColors.Red) }, onClick = { open = false; session.stopScreen() })
        }
    }
}

private val Live = MeetColors.Red

/**
 * The screen share picker: windows ("Приложения") and displays ("Экраны") as tiles with live thumbnails, and the
 * stream's sound, resolution and frame rate. [live]: a share is running, and the choice replaces it.
 */
@Composable
private fun ScreenPicker(session: MeetSession, live: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenPickerContent(session, live, onClose)
    }
}

@Composable
internal fun ScreenPickerContent(session: MeetSession, live: Boolean, onClose: () -> Unit) {
    // Listing windows waits for the system (ScreenCaptureKit): off the UI thread.
    var refresh by remember { mutableStateOf(0) }
    var loaded by remember(refresh) { mutableStateOf<List<ScreenChoice>?>(null) }
    LaunchedEffect(refresh) { loaded = withContext(Dispatchers.Default) { runCatching { session.screens() }.getOrDefault(emptyList()) } }
    val choices = loaded.orEmpty()
    val windows = choices.filter { it.isWindow }
    val displays = choices.filter { !it.isWindow }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(loaded) {
        if (loaded != null) {
            if (windows.isEmpty()) tab = 1
            else if (displays.isEmpty()) tab = 0
        }
    }
    var selected by remember(refresh) { mutableStateOf<ScreenChoice?>(null) }
    LaunchedEffect(loaded) { if (selected == null) selected = choices.singleOrNull() }
    var withAudio by remember { mutableStateOf(false) }
    val previewSources = if (windows.isEmpty() || displays.isEmpty()) choices else if (tab == 0) windows else displays
    val thumbs = remember(refresh) { mutableStateMapOf<ScreenChoice, ImageBitmap?>() }
    val availableWindows = windows.filter { it !in thumbs || thumbs[it] != null }
    val shown = previewSources.filter { !it.isWindow || it in availableWindows }
    val hiddenWindows = windows.size - availableWindows.size
    val ready = selected?.let { it in choices && (!isDesktop || it.source == null || thumbs[it] != null) } == true
    // Each thumbnail starts a capture for a moment: windows are taken once, the few displays every 5 s.
    // Keep the effect keyed to the original sources: hiding a failed card must not cancel another capture.
    LaunchedEffect(refresh, previewSources) {
        if (!isDesktop) return@LaunchedEffect // ReplayKit/MediaProjection ask on start and cannot be previewed.
        while (true) {
            for (batch in previewSources.chunked(2)) {
                coroutineScope {
                    for (c in batch) {
                        if (c.source == null || (c.isWindow && c in thumbs)) continue
                        launch {
                            val frame = withContext(Dispatchers.Default) { runCatching { session.screenThumbnail(c) }.getOrNull() }
                            thumbs[c] = frame?.let { FrameBitmap().apply { write(it) }.image }
                            if (frame == null && selected === c) selected = null
                        }
                    }
                }
            }
            if (previewSources.all { it.isWindow || it.source == null }) break
            delay(5_000)
        }
    }
    Surface(
        Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f).widthIn(max = 980.dp),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Сменить трансляцию" else "Демонстрация экрана", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { refresh++ }, enabled = loaded != null) { Text("Обновить") }
            }
            if (windows.isNotEmpty() && displays.isNotEmpty()) {
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Приложения · ${availableWindows.size}") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Экраны · ${displays.size}") })
                }
            }
            if (loaded == null) Text("Ищу окна и экраны…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hiddenWindows > 0) Text("Не удалось захватить окон: $hiddenWindows. Нажмите «Обновить», чтобы повторить проверку.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loaded != null && shown.isEmpty()) Text("Нет доступных окон. Откройте нужное окно или выберите экран целиком.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            choices.firstNotNullOfOrNull { it.note }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LazyVerticalGrid(GridCells.Adaptive(minSize = 220.dp), Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.source?.let { s -> "${s.isWindow}/${s.id}" } ?: it.label }) { c ->
                    val isSelected = selected === c
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier.clip(shape).clickable(enabled = !isDesktop || c !in thumbs || thumbs[c] != null || c.source == null) { selected = c }
                            .border(BorderStroke(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent), shape)
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111216)), contentAlignment = Alignment.Center) {
                            val image = thumbs[c]
                            when {
                                image != null -> Image(image, c.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                c.source == null || !isDesktop -> Text(if (c.isWindow) "Окно" else "Экран", color = Color.Gray)
                                c in thumbs -> Text("Источник недоступен", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                                else -> Text("Загружаю превью…", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            StreamSettings(session, withAudio, window = selected?.isWindow == true) { withAudio = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onClose) { Text("Отмена") }
                Button(
                    onClick = { if (ready) { selected?.let { session.startScreen(it, withAudio) }; onClose() } },
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = Live),
                ) { Text(if (live) "Сменить" else "Начать трансляцию") }
            }
        }
    }
}

/** Sound, resolution and frame rate of the stream. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamSettings(session: MeetSession, withAudio: Boolean, window: Boolean, onAudio: (Boolean) -> Unit) {
    val fps by session.screenFps.collectAsState()
    val height by session.screenHeight.collectAsState()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(withAudio, onAudio)
            // A window shares only its application's sound (macOS, Windows; elsewhere the whole system's).
            Text(if (window) "Звук приложения" else "Звук системы")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Разрешение", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeetSession.SCREEN_HEIGHT_CHOICES.forEach { choice ->
                FilterChip(selected = height == choice, onClick = { session.setScreenHeight(choice) }, label = { Text(if (choice == MeetSession.SOURCE_HEIGHT) "Исходное" else "${choice}p") })
            }
        }
        // 15 is enough for slides and code, 30 and 60 for video and scrolling.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Кадров в секунду", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeetSession.SCREEN_FPS_CHOICES.forEach { choice ->
                FilterChip(selected = fps == choice, onClick = { session.setScreenFps(choice) }, label = { Text("$choice") })
            }
        }
    }
}

/** The settings button and its window: devices, input mode, microphone processing and the rest. */
@Composable
private fun SoundMenu(session: MeetSession, size: Dp) {
    var open by remember { mutableStateOf(false) }
    ControlButton(MeetIcons.Settings, "Настройки", { open = true }, size = size)
    if (open) SettingsDialog(session) { open = false }
}

@Composable
private fun SettingsDialog(session: MeetSession, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            shape = RoundedCornerShape(12.dp), color = MeetColors.Panel,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Настройки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    ControlButton(MeetIcons.Close, "Закрыть", onClose, tone = Tone.GHOST, size = 36.dp)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (session.settings?.testAudio == false && session.audioDeviceChoice) {
                        SettingsSection("Устройства")
                        AudioDevicePickers(session)
                    }
                    InputModeSettings(session)
                    val processing by session.audioProcessing.collectAsState()
                    SettingsSection("Обработка микрофона")
                    NoiseSuppressionPicker(processing.noiseSuppression) { session.setAudioProcessing(processing.copy(noiseSuppression = it)) }
                    SwitchRow("Эхоподавление", processing.echoCancellation, "Уменьшает эхо от ваших динамиков") { session.setAudioProcessing(processing.copy(echoCancellation = it)) }
                    SwitchRow("Адаптивная громкость", processing.autoGainControl, "Выравнивает громкость голоса автоматически") { session.setAudioProcessing(processing.copy(autoGainControl = it)) }
                    SettingsSection("Прочее")
                    val cues by session.cues.collectAsState()
                    SwitchRow("Звуки входа, выхода и микрофона", cues, onChange = session::setCues)
                    val upload by session.logUpload.collectAsState()
                    SwitchRow("Отправлять журнал на сервер", upload, "Помогает разобрать проблемы со связью", session::setLogUpload)
                    val updates by session.updateCheck.collectAsState()
                    SwitchRow("Проверять обновления при запуске", updates, onChange = session::setUpdateCheck)
                    Text("Версия ${BuildInfo.VERSION}", style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                }
            }
        }
    }
}

/** A single choice: selecting a denoiser also turns the other one off. */
@Composable
internal fun NoiseSuppressionPicker(selected: NoiseSuppressionMode, onSelect: (NoiseSuppressionMode) -> Unit) {
    Text("Шумоподавление", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for ((mode, label, note) in listOf(
            Triple(NoiseSuppressionMode.OFF, "Выключено", "Без фильтрации фонового шума"),
            Triple(NoiseSuppressionMode.STANDARD, "Обычное", "Снижает постоянный фоновый шум"),
            Triple(NoiseSuppressionMode.STRONG, "Сильное", "Для кулера, клавиатуры и других фоновых звуков"),
        )) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (selected == mode) MeetColors.Background else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** A setting: its name (and a note) on the left, a switch on the right; the whole row toggles it. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted)
        }
        Switch(checked, onChange)
    }
}

/** When the microphone transmits: always, by voice (with a threshold and a live level meter) or by push-to-talk. */
@Composable
private fun InputModeSettings(session: MeetSession) {
    val input by session.inputSettings.collectAsState()
    val level by session.inputLevel.collectAsState()
    val transmitting by session.transmitting.collectAsState()
    SettingsSection("Режим ввода")
    for ((mode, label) in listOf(
        InputMode.OPEN to "Микрофон всегда включён",
        InputMode.VOICE_ACTIVITY to "Активация по голосу",
        InputMode.PUSH_TO_TALK to (if (isDesktop) "Нажми и говори (клавиша `)" else "Нажми и говори"),
    )) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { session.setInputSettings(input.copy(mode = mode)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = input.mode == mode, onClick = { session.setInputSettings(input.copy(mode = mode)) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    // The meter: the level now (green while it gets through), with the threshold as the slider under it.
    Text("Уровень микрофона", style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MeetColors.Background)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((level / METER_FULL_SCALE).coerceIn(0f, 1f)).background(if (transmitting) Speaking else MeetColors.TextMuted))
    }
    if (input.mode == InputMode.VOICE_ACTIVITY) {
        Text("Порог срабатывания: говорите и подвиньте его чуть ниже уровня голоса", style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
        Slider(input.threshold, { session.setInputSettings(input.copy(threshold = it)) }, valueRange = 0f..METER_FULL_SCALE)
    }
}

/** The level that fills the input meter; speech peaks rarely go above half of full scale. */
private const val METER_FULL_SCALE = 0.5f

@Composable
private fun ChatPanel(session: MeetSession, modifier: Modifier, onTyping: (Boolean) -> Unit = {}) {
    val messages by session.chat.collectAsState()
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    val send = { if (session.sendChat(draft)) draft = "" }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(MeetColors.Panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Чат", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
            ControlButton(MeetIcons.Close, "Закрыть чат", { session.setChatOpen(false) }, tone = Tone.GHOST, size = 32.dp)
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
                draft, { draft = it }, placeholder = { Text("Сообщение") }, singleLine = true,
                modifier = Modifier.weight(1f).onFocusChanged { onTyping(it.isFocused) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
            ControlButton(MeetIcons.Send, "Отправить", { send() }, tone = if (draft.isNotBlank()) Tone.ACTIVE else Tone.NORMAL, enabled = draft.isNotBlank(), size = 44.dp)
        }
    }
}

@Composable
private fun RemoteTile(session: MeetSession, p: RemoteParticipant, pinned: Boolean, expanded: Boolean, onExpand: (() -> Unit)?, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    var volumeOpen by remember(p.id) { mutableStateOf(false) }
    LaunchedEffect(compact) { if (compact) volumeOpen = false }
    val video by p.video.collectAsState()
    val speaking by p.speaking.collectAsState()
    val audioMuted by p.audioMuted.collectAsState()
    val videoMuted by p.videoMuted.collectAsState()
    val deafened by p.deafened.collectAsState()
    val quality = session.connection.collectAsState().value?.peers?.get(p.id)
    // The SFU sends the simulcast layer that fills this tile; none needed while it is off screen.
    DisposableEffect(p) { onDispose { session.setTileHeight(p, 0) } }
    Tile(
        video.takeIf { !videoMuted }, p.name, speaking && !audioMuted,
        modifier.onSizeChanged { session.setTileHeight(p, it.height) },
        muted = audioMuted, deafened = deafened, pinned = pinned, expanded = expanded, onExpand = onExpand, onClick = onClick, compact = compact,
        warning = quality?.takeIf { it != LinkQuality.GOOD }?.let { "связь ${it.label()}" },
        controlsOpen = volumeOpen,
        actions = { VolumeMenu(session, p, screen = false, open = volumeOpen, onOpenChange = { volumeOpen = it }) },
    )
}

/**
 * A participant's screen share: the picture when watched, otherwise an offer to watch (nothing is received
 * meanwhile).
 */
@Composable
private fun RemoteScreenTile(session: MeetSession, p: RemoteParticipant, pinned: Boolean, expanded: Boolean, onExpand: (() -> Unit)?, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    val screen by p.screen.collectAsState()
    val subscribed by p.screenSubscribed.collectAsState()
    val sound by p.screenAudio.collectAsState()
    var volumeOpen by remember(p.id) { mutableStateOf(false) }
    LaunchedEffect(compact, subscribed) { if (compact || !subscribed) volumeOpen = false }
    Tile(
        screen.takeIf { subscribed }, p.name, speaking = false, modifier, fit = true, placeholder = !subscribed, suffix = " — экран",
        pinned = pinned, expanded = expanded, onExpand = onExpand?.takeIf { subscribed }, onClick = onClick, compact = compact,
        controlsOpen = volumeOpen,
        actions = if (!subscribed) null else ({
            // Always there: the volume is kept and applied once the stream's sound arrives.
            VolumeMenu(session, p, screen = true, hasSound = sound != null, open = volumeOpen, onOpenChange = { volumeOpen = it })
            TileAction(MeetIcons.EyeOff, "Не смотреть стрим", { session.setScreenSubscribed(p, false) })
        }),
        badge = { hovered -> if (subscribed) StreamBadge(screen, details = hovered) },
    ) {
        if (!subscribed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
                if (!compact) Avatar(p.name, 56.dp)
                Text("${p.name} в эфире", color = Color.White, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium)
                Text(
                    "Смотреть стрим", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MeetColors.Accent).clickable { session.setScreenSubscribed(p, true) }
                        .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 4.dp else 8.dp),
                )
            }
        }
    }
}

/** The volume chip over a tile: an icon (and the level when it is not 100 %), the slider in its menu. */
@Composable
private fun VolumeMenu(session: MeetSession, p: RemoteParticipant, screen: Boolean, hasSound: Boolean = true, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val volumes by session.volumes.collectAsState()
    val volume = volumes[MeetSession.volumeKey(p.id, screen)] ?: 1f
    TileVolumeMenu(volume, screen, hasSound, open, onOpenChange) { session.setVolume(p, it, screen) }
}

@Composable
internal fun TileVolumeMenu(volume: Float, screen: Boolean, hasSound: Boolean, open: Boolean, onOpenChange: (Boolean) -> Unit, onVolumeChange: (Float) -> Unit) {
    Box {
        TileAction(
            if (volume == 0f) MeetIcons.VolumeOff else MeetIcons.VolumeUp,
            if (screen) "Громкость стрима" else "Громкость",
            { onOpenChange(true) },
            label = if (volume == 1f) null else "${(volume * 100).roundToInt()} %",
            tint = if (volume == 0f) MeetColors.Red else Color.White,
        )
        DropdownMenu(open, onDismissRequest = { onOpenChange(false) }) { VolumeControls(volume, screen, hasSound, onVolumeChange) }
    }
}

/** A volume slider (0–300 %) for a participant's voice or its stream's sound, for menus. */
@Composable
private fun VolumeControls(session: MeetSession, p: RemoteParticipant, screen: Boolean, hasSound: Boolean = true) {
    val volumes by session.volumes.collectAsState()
    val volume = volumes[MeetSession.volumeKey(p.id, screen)] ?: 1f
    VolumeControls(volume, screen, hasSound) { session.setVolume(p, it, screen) }
}

@Composable
private fun VolumeControls(volume: Float, screen: Boolean, hasSound: Boolean, onVolumeChange: (Float) -> Unit) {
    Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (screen) "Звук стрима" else "Громкость голоса", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(volume * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = MeetColors.TextMuted)
        }
        if (!hasSound) Text("Стрим идёт без звука", style = MaterialTheme.typography.labelSmall, color = MeetColors.TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (volume == 0f) MeetIcons.VolumeOff else MeetIcons.VolumeUp, if (volume == 0f) "Включить" else "Выключить",
                Modifier.size(20.dp).clip(CircleShape).clickable { onVolumeChange(if (volume == 0f) 1f else 0f) },
                tint = if (volume == 0f) MeetColors.Red else MeetColors.Text,
            )
            Slider(volume, onVolumeChange, valueRange = 0f..MeetSession.MAX_VOLUME, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
}

/** "В ЭФИРЕ" on a stream; with [details] also its size and the frame rate actually arriving. */
@Composable
private fun StreamBadge(track: MediaStreamTrack?, details: Boolean) {
    val native = track as? NativeTrack
    var text by remember(native) { mutableStateOf("") }
    if (native != null) LaunchedEffect(native) {
        var last = native.videoStats().frames
        while (true) {
            delay(1_000)
            val s = native.videoStats()
            text = if (s.width > 0) "${s.width}×${s.height} · ${s.frames - last} к/с" else ""
            last = s.frames
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(MeetColors.Scrim).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveBadge()
        if (details && text.isNotEmpty()) Text(text, color = Color.White, fontSize = 11.sp)
    }
}

/**
 * A video tile: the picture (or an avatar), the name with mute and pin marks, a ring while speaking. Its controls
 * ([actions], full screen) show only while the pointer is over it on desktop, always on touch screens; it never
 * lights up on hover. [content] draws over the picture (e.g. an offer to watch).
 */
@Composable
internal fun Tile(
    track: MediaStreamTrack?,
    name: String,
    speaking: Boolean,
    modifier: Modifier,
    mirror: Boolean = false,
    muted: Boolean = false,
    fit: Boolean = false,
    placeholder: Boolean = false,
    pinned: Boolean = false,
    warning: String? = null,
    deafened: Boolean = false,
    expanded: Boolean = false,
    compact: Boolean = false,
    controlsOpen: Boolean = false,
    suffix: String = "",
    onExpand: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    badge: @Composable (hovered: Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // A Popup receives pointer events outside the tile. Keep its anchor composed until it is dismissed.
    val controls = (hovered || controlsOpen || !isDesktop) && !compact
    val shape = RoundedCornerShape(if (expanded) 0.dp else 8.dp)
    Box(
        modifier.clip(shape).background(MeetColors.TileBackground).hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
    ) {
        VideoView(track, Modifier.fillMaxSize(), mirror = mirror, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        if (track == null && !placeholder) Avatar(name.ifBlank { "?" }, if (compact) 36.dp else 80.dp, Modifier.align(Alignment.Center))
        content()
        if (speaking) Box(Modifier.matchParentSize().border(BorderStroke(if (compact) 2.dp else 3.dp, Speaking), shape))
        // The name, bottom left, with what is off.
        Column(Modifier.align(Alignment.BottomStart).padding(if (compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (warning != null && !compact) Text(
                warning, color = Color.Black, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MeetColors.Yellow).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(MeetColors.Scrim).padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val mark = if (compact) 12.dp else 14.dp
                when {
                    deafened -> Icon(MeetIcons.HeadsetOff, "Заглушён", Modifier.size(mark), tint = Color(0xFFF23F43))
                    muted -> Icon(MeetIcons.MicOff, "Микрофон выключен", Modifier.size(mark), tint = Color(0xFFF23F43))
                }
                if (pinned) Icon(MeetIcons.Pin, "Закреплено", Modifier.size(mark), tint = Color.White)
                Text(name + suffix, color = Color.White, fontSize = if (compact) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(if (compact) 4.dp else 8.dp)) { badge(hovered) }
        if (controls && (actions != null || onExpand != null)) {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions?.invoke(this)
                if (onExpand != null) TileAction(if (expanded) MeetIcons.FullscreenExit else MeetIcons.Fullscreen, if (expanded) "Свернуть (Esc)" else "Во весь экран", onExpand)
            }
        }
    }
}
