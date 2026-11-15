package io.github.siye.littletoolbox

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.ToolModule
import io.github.siye.littletoolbox.tools.audio.AudioTool
import io.github.siye.littletoolbox.tools.image.ImageTool
import io.github.siye.littletoolbox.tools.quicknote.QuickNoteTool
import io.github.siye.littletoolbox.tools.textstats.TextStatsTool
import io.github.siye.littletoolbox.tools.trail.TrailTool
import io.github.siye.littletoolbox.tools.wifi.WifiTool
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class ColorMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
internal enum class Palette(val title: String) {
    GRAPHITE("石墨灰"),
    LAVENDER("薰衣草"),
    IRIS("鸢尾"),
    PERIWINKLE("长春花"),
    SAGE("鼠尾草"),
    MIST("海雾"),
    SPRING("新芽绿"),
    CYAN("电光青"),
    VIOLET("电光紫"),
}
internal enum class TypeSize(val title: String, val scale: Float) {
    SMALL("小", 0.9f), STANDARD("标准", 1f), LARGE("大", 1.15f)
}
internal enum class DensityMode(val title: String, val gutter: Int) {
    COMPACT("紧凑", 16), STANDARD("标准", 22), RELAXED("宽松", 28)
}
internal enum class Motion(val title: String, val durationMillis: Int) {
    OFF("关闭", 0), LIGHT("轻柔", 140), STANDARD("标准", 220)
}

internal data class ToolCollection(val id: String, val name: String, val tools: List<String>)
internal data class ToolboxPreferences(
    val colorMode: ColorMode = ColorMode.SYSTEM,
    val palette: Palette = Palette.GRAPHITE,
    val typeSize: TypeSize = TypeSize.STANDARD,
    val density: DensityMode = DensityMode.STANDARD,
    val motion: Motion = Motion.STANDARD,
    val sounds: Boolean = false,
    val homeOrder: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val collections: List<ToolCollection> = emptyList(),
)

internal data class ToolEntry(val info: ToolInfo, val module: ToolModule? = null)

internal object ToolCatalog {
    val entries = listOf(
        WifiTool.entry(),
        TrailTool.entry(),
        QuickNoteTool.entry(),
        ImageTool.entry(),
        AudioTool.entry(),
        TextStatsTool.entry(),
    )

    fun find(id: String): ToolEntry? = entries.firstOrNull { it.info.id == id }

    fun ordered(order: List<String>): List<ToolEntry> =
        (order.mapNotNull(::find) + entries).distinctBy { it.info.id }

    fun search(query: String): List<ToolEntry> {
        val words = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return entries.filter { entry ->
            val text = with(entry.info) { (listOf(name, description, category.title) + keywords).joinToString(" ") }
            words.all { it in text.lowercase(Locale.ROOT) }
        }
    }

    private fun ToolModule.entry() = ToolEntry(info, this)
}

private val Context.toolboxStore by preferencesDataStore(name = "toolbox")
private object Keys {
    val colorMode = stringPreferencesKey("color-mode")
    val palette = stringPreferencesKey("palette")
    val typeSize = stringPreferencesKey("type-size")
    val density = stringPreferencesKey("layout-density")
    val motion = stringPreferencesKey("motion")
    val sounds = booleanPreferencesKey("button-sounds")
    val homeOrder = stringPreferencesKey("home-order")
    val recent = stringPreferencesKey("recent-tools")
    val collections = stringPreferencesKey("collections")
}

internal sealed interface LaunchRequest {
    data object QuickNote : LaunchRequest
    data class SharedText(val text: String) : LaunchRequest
    data class SharedMedia(val toolId: String, val uris: List<Uri>) : LaunchRequest
}

internal sealed interface ToolInput {
    data class Text(val value: String) : ToolInput
    data class Media(val uris: List<Uri>) : ToolInput
}

class ToolboxState(application: Application) : AndroidViewModel(application) {
    private val store = application.toolboxStore
    private val _launchRequest = MutableStateFlow<LaunchRequest?>(null)
    internal val launchRequest = _launchRequest.asStateFlow()
    private var sharedInput: Pair<String, ToolInput>? = null

    internal val preferences = store.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(::decode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolboxPreferences())

    internal fun openQuickNote() { _launchRequest.value = LaunchRequest.QuickNote }
    internal fun shareText(text: String) { _launchRequest.value = LaunchRequest.SharedText(text) }
    internal fun shareMedia(toolId: String, uris: List<Uri>) {
        _launchRequest.value = LaunchRequest.SharedMedia(toolId, uris)
    }
    internal fun clearRequest() { _launchRequest.value = null }

    internal fun stageToolInput(input: ToolInput): String =
        UUID.randomUUID().toString().also { token -> sharedInput = token to input }

    internal fun takeToolInput(token: String?): ToolInput? {
        val input = sharedInput
        return if (input != null && input.first == token) {
            sharedInput = null
            input.second
        } else null
    }

    internal fun colorMode(value: ColorMode) = save(Keys.colorMode, value.name)
    internal fun palette(value: Palette) = save(Keys.palette, value.name)
    internal fun typeSize(value: TypeSize) = save(Keys.typeSize, value.name)
    internal fun density(value: DensityMode) = save(Keys.density, value.name)
    internal fun motion(value: Motion) = save(Keys.motion, value.name)
    internal fun sounds(value: Boolean) = save(Keys.sounds, value)

    internal fun visit(id: String) = viewModelScope.launch {
        store.edit { data ->
            val previous = decodeStrings(data[Keys.recent])
            data[Keys.recent] = JSONArray((listOf(id) + previous).distinct().take(4)).toString()
        }
    }

    internal fun moveHomeTool(id: String, offset: Int) = viewModelScope.launch {
        store.edit { data ->
            val order = ToolCatalog.ordered(decodeStrings(data[Keys.homeOrder])).map { it.info.id }
            data[Keys.homeOrder] = JSONArray(move(order, id, offset)).toString()
        }
    }

    internal fun addCollection(name: String) = editCollections {
        it + ToolCollection(UUID.randomUUID().toString(), name, emptyList())
    }

    internal fun renameCollection(id: String, name: String) = editCollections { collections ->
        collections.map { if (it.id == id) it.copy(name = name) else it }
    }

    internal fun deleteCollection(id: String) = editCollections { collections -> collections.filter { it.id != id } }

    internal fun moveCollection(id: String, offset: Int) = editCollections { collections ->
        val byId = collections.associateBy { it.id }
        move(collections.map { it.id }, id, offset).mapNotNull(byId::get)
    }

    internal fun toggleCollectionTool(collectionId: String, toolId: String) = editCollections { collections ->
        collections.map { collection ->
            if (collection.id != collectionId) collection
            else collection.copy(
                tools = if (toolId in collection.tools) collection.tools - toolId else collection.tools + toolId,
            )
        }
    }

    internal fun moveCollectionTool(collectionId: String, toolId: String, offset: Int) = editCollections { collections ->
        collections.map { if (it.id == collectionId) it.copy(tools = move(it.tools, toolId, offset)) else it }
    }

    private fun <T> save(key: Preferences.Key<T>, value: T) = viewModelScope.launch {
        store.edit { it[key] = value }
    }

    private fun editCollections(edit: (List<ToolCollection>) -> List<ToolCollection>) = viewModelScope.launch {
        store.edit { data ->
            val output = JSONArray()
            edit(decodeCollections(data[Keys.collections])).forEach { collection ->
                output.put(JSONObject().put("id", collection.id).put("name", collection.name)
                    .put("tools", JSONArray(collection.tools)))
            }
            data[Keys.collections] = output.toString()
        }
    }

    private fun decode(prefs: Preferences) = ToolboxPreferences(
        colorMode = enumValue(prefs[Keys.colorMode], ColorMode.SYSTEM),
        palette = enumValue(prefs[Keys.palette], Palette.GRAPHITE),
        typeSize = enumValue(prefs[Keys.typeSize], TypeSize.STANDARD),
        density = enumValue(prefs[Keys.density], DensityMode.STANDARD),
        motion = enumValue(prefs[Keys.motion], Motion.STANDARD),
        sounds = prefs[Keys.sounds] ?: false,
        homeOrder = decodeStrings(prefs[Keys.homeOrder]),
        recent = decodeStrings(prefs[Keys.recent]),
        collections = decodeCollections(prefs[Keys.collections]),
    )
}

private fun move(items: List<String>, id: String, offset: Int): List<String> {
    val before = items.indexOf(id)
    val after = before + offset
    if (before < 0 || after !in items.indices) return items
    return items.toMutableList().apply { add(after, removeAt(before)) }
}

private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

private fun decodeStrings(source: String?): List<String> {
    val array = parseArray(source)
    return List(array.length()) { array.optString(it, "") }.filter(String::isNotEmpty)
}

private fun decodeCollections(source: String?): List<ToolCollection> {
    val array = parseArray(source)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val name = item.optString("name")
        if (id.isBlank() || name.isBlank()) null
        else ToolCollection(id, name, decodeStrings(item.optJSONArray("tools")?.toString()))
    }
}

private fun parseArray(source: String?): JSONArray =
    try {
        if (source.isNullOrEmpty()) JSONArray() else JSONArray(source)
    } catch (_: JSONException) {
        JSONArray()
    }
