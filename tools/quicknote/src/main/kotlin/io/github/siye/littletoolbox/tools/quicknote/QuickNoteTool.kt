package io.github.siye.littletoolbox.tools.quicknote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.ToolModule

object QuickNoteTool : ToolModule {
    override val info = ToolInfo(
        id = "quick-note",
        name = "即记",
        category = ToolCategory.TEXT,
        description = "随手写下想法，自动保存本地草稿。",
        keywords = listOf("便签", "笔记", "草稿", "备忘", "速记", "记事"),
        help = listOf(
            "打开即记后可直接输入。切到后台或退出后，再打开即可继续编辑已经写入的草稿。",
            "从其他应用分享的文字会追加到当前草稿，已有内容会保留。",
            "点击「保存便签」后，正文会出现在便签历史。轻点一条便签可以查看全文并复制。",
            "草稿和便签保存在本机，应用卸载时会一并删除。",
        ),
    )

    private var store: QuickNoteStore? = null

    @Composable
    override fun Content(initialText: String?) {
        val context = LocalContext.current
        val noteStore = remember(context.applicationContext) {
            store ?: QuickNoteStore(context.applicationContext).also { store = it }
        }
        val state by noteStore.state.collectAsState()
        var imported by rememberSaveable(initialText) { mutableStateOf(false) }
        var viewedNote by remember { mutableStateOf<SavedNote?>(null) }
        var focusedEditor by remember { mutableStateOf(false) }
        LaunchedEffect(initialText, state.ready, state.saving) {
            if (!imported && !initialText.isNullOrEmpty() && noteStore.importText(initialText)) {
                imported = true
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DraftEditor(
                    state = state,
                    shouldFocus = !focusedEditor,
                    onFocusRequested = { focusedEditor = true },
                    onChange = noteStore::updateDraft,
                    onSave = noteStore::saveNote,
                    onCopy = { copyText(context, state.draft) },
                    onRetry = noteStore::retry,
                )
            }

            if (state.ready) {
                item {
                    Text(
                        text = "便签历史",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (state.notes.isEmpty()) {
                    item {
                        Text(
                            text = "还没有保存的便签",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(state.notes, key = { it.id }) { note ->
                    Card(onClick = { viewedNote = note }, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = note.body,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = savedDate(context, note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        viewedNote?.let { note ->
            AlertDialog(
                onDismissRequest = { viewedNote = null },
                title = { Text(savedDate(context, note)) },
                text = {
                    SelectionContainer {
                        Text(
                            text = note.body,
                            modifier = Modifier.heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { copyText(context, note.body) }) { Text("复制") }
                },
                dismissButton = {
                    TextButton(onClick = { viewedNote = null }) { Text("关闭") }
                },
            )
        }
    }
}

@Composable
private fun DraftEditor(
    state: NoteState,
    shouldFocus: Boolean,
    onFocusRequested: () -> Unit,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val issueMessage = when (state.issue) {
        StorageIssue.Load -> "读取便签失败"
        StorageIssue.Draft -> "草稿写入失败，当前输入仍保留"
        StorageIssue.Save -> "便签保存失败，当前输入仍保留"
        null -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text("草稿") },
            placeholder = { Text("记下此刻的想法") },
            enabled = state.ready,
            readOnly = state.saving,
            minLines = 4,
            maxLines = 8,
        )
        if (issueMessage != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issueMessage, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        } else {
            Text(
                text = if (state.ready) "草稿会自动保存到本机" else "正在读取草稿…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopy, enabled = state.draft.isNotEmpty()) { Text("复制") }
            Button(onClick = onSave, enabled = state.draft.isNotBlank() && !state.saving) {
                Text(if (state.saving) "保存中…" else "保存便签")
            }
        }
    }

    LaunchedEffect(state.ready, windowFocused, shouldFocus) {
        if (state.ready && windowFocused && shouldFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusRequested()
        }
    }
}

private fun savedDate(context: Context, note: SavedNote): String =
    DateUtils.formatDateTime(
        context,
        note.savedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

private fun copyText(context: Context, body: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("即记", body)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}
