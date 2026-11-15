package io.github.siye.littletoolbox.tools.quicknote

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SavedNote(val id: Long, val body: String, val savedAt: Long)

internal data class NoteState(
    val ready: Boolean = false,
    val draft: String = "",
    val notes: List<SavedNote> = emptyList(),
    val saving: Boolean = false,
    val issue: StorageIssue? = null,
)

internal enum class StorageIssue {
    Load, Draft, Save,
}

// The UI and this queue run on Main. Only actual database work runs on IO.
internal class QuickNoteStore(context: Context) {
    private val application = context.applicationContext
    private val database by lazy { QuickNoteDatabase(application) }
    private val mutableState = MutableStateFlow(NoteState())
    private val writes = Channel<Operation>(Channel.UNLIMITED)
    private var draftWriteQueued = false

    val state = mutableState.asStateFlow()

    private sealed interface Operation {
        data object Load : Operation
        data object WriteDraft : Operation
        data class Save(val body: String) : Operation
    }

    init {
        // Keep queued writes alive when the editor leaves the composition.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            for (operation in writes) {
                when (operation) {
                    Operation.Load -> load()
                    Operation.WriteDraft -> writeDraft()
                    is Operation.Save -> save(operation.body)
                }
            }
        }
        writes.trySend(Operation.Load)
    }

    fun updateDraft(body: String) {
        val current = state.value
        if (!current.ready || current.saving || body == current.draft) return
        mutableState.value = current.copy(draft = body, issue = null)
        queueDraftWrite()
    }

    fun importText(text: String): Boolean {
        val current = state.value
        if (!current.ready || current.saving) return false
        updateDraft(if (current.draft.isEmpty()) text else "${current.draft}\n\n$text")
        return true
    }

    fun saveNote() {
        val current = state.value
        if (!current.ready || current.saving || current.draft.isBlank()) return
        mutableState.value = current.copy(saving = true, issue = null)
        writes.trySend(Operation.Save(current.draft))
    }

    fun retry() {
        when (state.value.issue) {
            StorageIssue.Load -> {
                mutableState.value = state.value.copy(issue = null)
                writes.trySend(Operation.Load)
            }
            StorageIssue.Draft -> queueDraftWrite()
            StorageIssue.Save -> saveNote()
            null -> Unit
        }
    }

    private fun queueDraftWrite() {
        if (draftWriteQueued) return
        draftWriteQueued = true
        writes.trySend(Operation.WriteDraft)
    }

    private suspend fun load() {
        try {
            mutableState.value = withContext(Dispatchers.IO) { database.read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            mutableState.value = NoteState(issue = StorageIssue.Load)
        }
    }

    private suspend fun writeDraft() {
        draftWriteQueued = false
        val body = state.value.draft
        try {
            withContext(Dispatchers.IO) { database.writeDraft(body) }
            val current = state.value
            if (current.draft == body && current.issue == StorageIssue.Draft) {
                mutableState.value = current.copy(issue = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            val current = state.value
            if (!current.saving && current.draft == body && !draftWriteQueued) {
                mutableState.value = current.copy(issue = StorageIssue.Draft)
            }
        }
    }

    private suspend fun save(body: String) {
        try {
            val note = withContext(Dispatchers.IO) { database.saveNote(body) }
            mutableState.value = NoteState(ready = true, notes = listOf(note) + state.value.notes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            mutableState.value = state.value.copy(saving = false, issue = StorageIssue.Save)
        }
    }
}
