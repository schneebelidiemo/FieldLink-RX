package ch.fieldlink.rx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.fieldlink.rx.R
import ch.fieldlink.rx.model.AudioInput
import ch.fieldlink.rx.model.AudioCaptureMode
import ch.fieldlink.rx.model.AudioCaptureSource
import ch.fieldlink.rx.model.Coordinates
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecoderDiagnostic
import ch.fieldlink.rx.model.DecoderStage
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.model.ReceiverPhase
import ch.fieldlink.rx.model.ReceiverState
import ch.fieldlink.rx.runtime.ReceiverRuntime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldLinkRxApp(
    onRefreshInputs: () -> Unit,
    onSelectInput: (Int) -> Unit,
    onSelectMode: (DecodeMode) -> Unit,
    onSelectAudioCaptureMode: (AudioCaptureMode) -> Unit,
    onStart: (String, Int?, DecodeMode, AudioCaptureMode) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
) {
    val state by ReceiverRuntime.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.phase == ReceiverPhase.LISTENING) {
                                stringResource(R.string.reception_active)
                            } else {
                                stringResource(R.string.reception_stopped)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (state.phase) {
            ReceiverPhase.NEEDS_PASSWORD -> SetupScreen(
                state = state,
                onRefreshInputs = onRefreshInputs,
                onSelectInput = onSelectInput,
                onSelectMode = onSelectMode,
                onSelectAudioCaptureMode = onSelectAudioCaptureMode,
                onStart = onStart,
                modifier = Modifier.padding(padding),
            )
            else -> ReceiverScreen(
                state = state,
                onStop = onStop,
                onNewSession = onNewSession,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SetupScreen(
    state: ReceiverState,
    onRefreshInputs: () -> Unit,
    onSelectInput: (Int) -> Unit,
    onSelectMode: (DecodeMode) -> Unit,
    onSelectAudioCaptureMode: (AudioCaptureMode) -> Unit,
    onStart: (String, Int?, DecodeMode, AudioCaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var modeMenuOpen by remember { mutableStateOf(false) }
    var captureModeMenuOpen by remember { mutableStateOf(false) }
    val selected = state.inputs.firstOrNull { it.id == state.selectedInputId }
    val fieldLinkSelected = state.selectedMode.isFieldLink()
    val passwordValid = !fieldLinkSelected || password.isEmpty() || password.length >= 16

    LaunchedEffect(Unit) { onRefreshInputs() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.session_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.session_explanation), style = MaterialTheme.typography.bodyMedium)

        Text(stringResource(R.string.choose_decoder), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { modeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.selectedMode?.let { modeLabel(it) } ?: stringResource(R.string.decoder_not_selected))
            }
            DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                DecodeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(modeLabel(mode)) },
                        onClick = {
                            onSelectMode(mode)
                            modeMenuOpen = false
                        },
                    )
                }
            }
        }

        if (fieldLinkSelected) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.password_label)) },
                supportingText = { Text(stringResource(R.string.password_minimum)) },
                isError = !passwordValid,
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    OutlinedButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.password_hide else R.string.password_show))
                    }
                },
            )
        }

        Text(stringResource(R.string.choose_audio_input), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected?.let(::inputLabel) ?: stringResource(R.string.audio_input))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.inputs.forEach { input ->
                        DropdownMenuItem(
                            text = { Text(inputLabel(input)) },
                            onClick = {
                                onSelectInput(input.id)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onRefreshInputs) {
                Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
            }
        }

        Text(stringResource(R.string.choose_audio_processing), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { captureModeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(captureModeLabel(state.audioCaptureMode))
            }
            DropdownMenu(expanded = captureModeMenuOpen, onDismissRequest = { captureModeMenuOpen = false }) {
                AudioCaptureMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(captureModeLabel(mode)) },
                        onClick = {
                            onSelectAudioCaptureMode(mode)
                            captureModeMenuOpen = false
                        },
                    )
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                state.selectedMode?.let { mode ->
                    onStart(
                        if (mode.isFieldLink()) password else "",
                        state.selectedInputId,
                        mode,
                        state.audioCaptureMode,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = passwordValid && state.selectedInputId != null && state.selectedMode != null,
        ) {
            Text(stringResource(R.string.start_reception))
        }
    }
}

@Composable
private fun ReceiverScreen(
    state: ReceiverState,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReceiverOverview(state, onStop, onNewSession, Modifier.weight(1f).fillMaxHeight())
                MessageList(state, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ReceiverOverview(state, onStop, onNewSession, Modifier.fillMaxWidth()) }
                item { MessageHeader(state.messages.size) }
                if (state.messages.isEmpty()) item { Text(stringResource(R.string.no_messages)) }
                items(state.messages, key = { it.id }) { MessageCard(it) }
            }
        }
    }
}

@Composable
private fun ReceiverOverview(
    state: ReceiverState,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(phaseText(state.phase), fontWeight = FontWeight.SemiBold)
                    if (state.phase == ReceiverPhase.STARTING) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp))
                }
                Text(state.inputs.firstOrNull { it.id == state.selectedInputId }?.let(::inputLabel).orEmpty())
                Text("${stringResource(R.string.signal_level)}: ${"%.1f".format(state.signal.rmsDb)} dBFS")
                Text("${stringResource(R.string.audio_frequency)}: ${"%.1f".format(state.signal.peakFrequencyHz)} Hz")
                Text(
                    "${stringResource(R.string.selected_decoder)}: " +
                        (state.selectedMode?.let { modeLabel(it) } ?: stringResource(R.string.decoder_not_selected)),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.captureInfo?.let { info ->
                    Text(
                        stringResource(
                            R.string.audio_capture_info,
                            info.sampleRateHz,
                            captureSourceLabel(info.source),
                            info.routedDevice.ifBlank { stringResource(R.string.audio_device_unknown) },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.selectedMode.isFieldLink()) {
                    Text(
                        "${stringResource(R.string.decoder_status)}: ${diagnosticText(state.decoderDiagnostic)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Waterfall(state.waterfall, Modifier.fillMaxWidth())

        state.partialTexts.filterValues { it.isNotBlank() }.forEach { (mode, text) ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(mode.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.phase == ReceiverPhase.LISTENING || state.phase == ReceiverPhase.STARTING) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stop_reception))
            }
        } else {
            Button(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.new_session))
            }
        }
    }
}

@Composable
private fun MessageList(state: ReceiverState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { MessageHeader(state.messages.size) }
        if (state.messages.isEmpty()) item { Text(stringResource(R.string.no_messages)) }
        items(state.messages, key = { it.id }) { MessageCard(it) }
    }
}

@Composable
private fun MessageHeader(count: Int) {
    Text("${stringResource(R.string.messages)} ($count)", style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun MessageCard(message: DecodedMessage) {
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(message.mode.displayName, fontWeight = FontWeight.Bold)
                Text(formatter.format(message.receivedAt), style = MaterialTheme.typography.labelMedium)
            }
            message.callsign?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                if (message.encryptedWithoutKey) stringResource(R.string.encrypted_message) else message.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${"%.1f".format(message.audioFrequencyHz)} Hz") })
                AssistChip(onClick = {}, label = { Text("${(message.quality * 100).toInt()} %") })
                if (message.uncertain) AssistChip(onClick = {}, label = { Text(stringResource(R.string.uncertain)) })
            }
            message.coordinates?.let { coordinates ->
                Text("${coordinates.latitude}, ${coordinates.longitude}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { copyMessage(context, message) }) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                }
                IconButton(onClick = { shareMessage(context, message) }) {
                    Icon(Icons.Default.Share, stringResource(R.string.action_share))
                }
                if (message.coordinates != null) {
                    IconButton(onClick = { openMap(context, message.coordinates) }) {
                        Icon(Icons.Default.Map, stringResource(R.string.action_map))
                    }
                }
            }
        }
    }
}

@Composable
private fun phaseText(phase: ReceiverPhase): String = when (phase) {
    ReceiverPhase.STARTING -> stringResource(R.string.reception_starting)
    ReceiverPhase.LISTENING -> stringResource(R.string.reception_active)
    ReceiverPhase.ERROR -> stringResource(R.string.service_error)
    else -> stringResource(R.string.reception_stopped)
}

private fun DecodeMode?.isFieldLink(): Boolean =
    this == DecodeMode.FIELDLINK_FAST || this == DecodeMode.FIELDLINK_WIDE

@Composable
private fun captureSourceLabel(source: AudioCaptureSource): String = stringResource(
    when (source) {
        AudioCaptureSource.UNPROCESSED -> R.string.audio_source_unprocessed
        AudioCaptureSource.VOICE_RECOGNITION -> R.string.audio_source_voice_recognition
        AudioCaptureSource.MICROPHONE -> R.string.audio_source_microphone
        AudioCaptureSource.OTHER -> R.string.audio_source_other
    },
)

@Composable
private fun diagnosticText(diagnostic: DecoderDiagnostic): String = when (diagnostic.stage) {
    DecoderStage.WAITING -> stringResource(R.string.decoder_waiting_fieldlink)
    DecoderStage.PREAMBLE -> stringResource(
        R.string.decoder_preamble,
        diagnostic.preambleMatches ?: 0,
        32,
        diagnostic.syncMatches ?: 0,
        8,
    )
    DecoderStage.FRAME -> stringResource(R.string.decoder_frame)
    DecoderStage.SUCCESS -> stringResource(R.string.decoder_success)
    DecoderStage.ENCRYPTED -> stringResource(R.string.decoder_encrypted)
    DecoderStage.DAMAGED -> diagnostic.detail?.let {
        stringResource(R.string.decoder_damaged_detail, it)
    } ?: stringResource(R.string.decoder_damaged)
}

@Composable
private fun captureModeLabel(mode: AudioCaptureMode): String = stringResource(
    when (mode) {
        AudioCaptureMode.AUTOMATIC -> R.string.audio_capture_automatic
        AudioCaptureMode.UNPROCESSED -> R.string.audio_capture_unprocessed
        AudioCaptureMode.VOICE_RECOGNITION -> R.string.audio_capture_voice_recognition
        AudioCaptureMode.MICROPHONE -> R.string.audio_capture_microphone
    },
)

@Composable
private fun modeLabel(mode: DecodeMode): String = stringResource(
    when (mode) {
        DecodeMode.FIELDLINK_FAST -> R.string.mode_fieldlink_fast
        DecodeMode.FIELDLINK_WIDE -> R.string.mode_fieldlink_wide
        DecodeMode.CW -> R.string.mode_cw
        DecodeMode.RTTY -> R.string.mode_rtty
        DecodeMode.PSK31 -> R.string.mode_psk31
        DecodeMode.PSK63 -> R.string.mode_psk63
        DecodeMode.FT8 -> R.string.mode_ft8
        DecodeMode.FT4 -> R.string.mode_ft4
        DecodeMode.JS8 -> R.string.mode_js8
    },
)

private fun inputLabel(input: AudioInput): String = "${input.productName} · ${input.typeName}"

private fun messageText(message: DecodedMessage): String = buildString {
    message.callsign?.let { append(it).append(": ") }
    append(message.text)
    message.coordinates?.let { append("\n").append(it.latitude).append(", ").append(it.longitude) }
}

private fun copyMessage(context: Context, message: DecodedMessage) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("FieldLink RX", messageText(message)))
    Toast.makeText(context, R.string.copy_success, Toast.LENGTH_SHORT).show()
}

private fun shareMessage(context: Context, message: DecodedMessage) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, messageText(message))
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_message)))
}

private fun openMap(context: Context, coordinates: Coordinates) {
    val query = Uri.encode("${coordinates.latitude},${coordinates.longitude}")
    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${coordinates.latitude},${coordinates.longitude}?q=$query"))
        .setPackage("com.google.android.apps.maps")
    if (geoIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(geoIntent)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${coordinates.latitude},${coordinates.longitude}?q=$query")))
    }
}
