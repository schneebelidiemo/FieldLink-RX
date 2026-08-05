package ch.fieldlink.rx.runtime

import ch.fieldlink.rx.model.AudioInput
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.model.ReceiverPhase
import ch.fieldlink.rx.model.ReceiverState
import ch.fieldlink.rx.model.SignalSnapshot
import ch.fieldlink.rx.model.SpectrumFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ReceiverRuntime {
    private const val MAX_MESSAGES = 250
    private const val MAX_WATERFALL_ROWS = 120

    private val lock = Any()
    private var password: CharArray? = null

    private val mutableState = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = mutableState.asStateFlow()

    fun configure(passwordText: String, selectedInputId: Int?) {
        require(passwordText.length >= 16) { "The group password must contain at least 16 characters." }
        synchronized(lock) {
            password?.fill('\u0000')
            password = passwordText.toCharArray()
        }
        mutableState.update {
            it.copy(
                phase = ReceiverPhase.STARTING,
                selectedInputId = selectedInputId,
                error = null,
            )
        }
    }

    fun passwordCopy(): CharArray? = synchronized(lock) { password?.copyOf() }

    fun setInputs(inputs: List<AudioInput>) {
        mutableState.update { current ->
            val selected = current.selectedInputId?.takeIf { id -> inputs.any { it.id == id } }
                ?: inputs.firstOrNull { it.isBuiltIn }?.id
                ?: inputs.firstOrNull()?.id
            current.copy(inputs = inputs, selectedInputId = selected)
        }
    }

    fun selectInput(id: Int) {
        mutableState.update { it.copy(selectedInputId = id) }
    }

    fun listening() {
        mutableState.update { it.copy(phase = ReceiverPhase.LISTENING, error = null) }
    }

    fun signal(snapshot: SignalSnapshot, frame: SpectrumFrame?) {
        mutableState.update { current ->
            val rows = if (frame == null) current.waterfall else (current.waterfall + frame).takeLast(MAX_WATERFALL_ROWS)
            current.copy(signal = snapshot, waterfall = rows)
        }
    }

    fun partial(mode: DecodeMode, text: String) {
        mutableState.update { current -> current.copy(partialTexts = current.partialTexts + (mode to text.takeLast(1_000))) }
    }

    fun addMessage(message: DecodedMessage): Boolean {
        var isNew = false
        mutableState.update { current ->
            if (current.messages.any { it.id == message.id }) {
                current
            } else {
                isNew = true
                current.copy(messages = (listOf(message) + current.messages).take(MAX_MESSAGES))
            }
        }
        return isNew
    }

    fun error(message: String) {
        mutableState.update { it.copy(phase = ReceiverPhase.ERROR, error = message) }
    }

    fun stopped() {
        synchronized(lock) {
            password?.fill('\u0000')
            password = null
        }
        mutableState.update {
            it.copy(
                phase = ReceiverPhase.STOPPED,
                signal = SignalSnapshot(),
                waterfall = emptyList(),
                partialTexts = emptyMap(),
            )
        }
    }

    fun requireNewPassword() {
        synchronized(lock) {
            password?.fill('\u0000')
            password = null
        }
        mutableState.update {
            it.copy(
                phase = ReceiverPhase.NEEDS_PASSWORD,
                signal = SignalSnapshot(),
                waterfall = emptyList(),
                partialTexts = emptyMap(),
                error = null,
            )
        }
    }
}
