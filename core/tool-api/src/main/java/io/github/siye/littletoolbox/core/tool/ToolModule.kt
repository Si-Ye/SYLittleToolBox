package io.github.siye.littletoolbox.core.tool

import androidx.compose.runtime.Composable

enum class ToolCategory(val title: String) {
    NETWORK("网络"),
    TRAVEL("出行"),
    TEXT("文字与记录"),
    IMAGE("图像"),
    MEDIA("音视频"),
}

data class ToolInfo(
    val id: String,
    val name: String,
    val category: ToolCategory,
    val description: String,
    val keywords: List<String>,
    val help: List<String>,
)

interface ToolModule {
    val info: ToolInfo

    @Composable
    fun Content(initialText: String?)
}
