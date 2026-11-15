package io.github.siye.littletoolbox

import android.view.SoundEffectConstants
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.UriInputTool

private enum class Tab(val route: String, val title: String, val icon: Int) {
    HOME("home", "首页", R.drawable.ic_home),
    CATEGORIES("categories", "分类", R.drawable.ic_categories),
    SEARCH("search", "搜索", R.drawable.ic_search),
    COLLECTIONS("collections", "组合", R.drawable.ic_collections),
    SETTINGS("settings", "设置", R.drawable.ic_settings),
}

private const val TOOL = "tool/{toolId}?input={input}"
private const val TOOL_HELP = "tool-help/{toolId}"
private const val COLLECTION = "collection/{id}"
private const val CATEGORY = "category/{category}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolboxApp(state: ToolboxState) {
    val settings by state.preferences.collectAsStateWithLifecycle()
    val request by state.launchRequest.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val selectedTool = current?.arguments?.getString("toolId")?.let(ToolCatalog::find)
    val view = LocalView.current

    fun sound() {
        if (settings.sounds) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    fun openTool(id: String, payload: ToolInput? = null) {
        val entry = ToolCatalog.find(id) ?: return
        sound()
        if (entry.module != null) state.visit(id)
        val input = payload?.let(state::stageToolInput)
        nav.navigate(if (input == null) "tool/$id" else "tool/$id?input=$input") {
            launchSingleTop = input == null
        }
    }

    LaunchedEffect(request) {
        when (val event = request) {
            LaunchRequest.QuickNote -> {
                state.clearRequest()
                openTool(QUICK_NOTE_ID)
            }
            is LaunchRequest.SharedMedia -> {
                state.clearRequest()
                openTool(event.toolId, ToolInput.Media(event.uris))
            }
            else -> Unit
        }
    }

    ToolboxTheme(settings) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                if (route != null && tab == null) {
                    val title = when (route) {
                        TOOL -> selectedTool?.info?.name ?: "工具"
                        TOOL_HELP -> "${selectedTool?.info?.name ?: "工具"}帮助"
                        COLLECTION -> settings.collections.firstOrNull {
                            it.id == current?.arguments?.getString("id")
                        }?.name ?: "组合"
                        CATEGORY -> categoryFor(current?.arguments?.getString("category"))?.title ?: "分类"
                        "home-order" -> "首页排序"
                        "palettes" -> "主题色"
                        "permissions" -> "应用权限"
                        "help" -> "使用帮助"
                        "about" -> "关于一袖"
                        else -> "一袖"
                    }
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(painterResource(R.drawable.ic_back), "返回")
                            }
                        },
                        actions = {
                            if (route == TOOL && !selectedTool?.info?.help.isNullOrEmpty()) {
                                TextButton(onClick = { nav.navigate("tool-help/${selectedTool?.info?.id}") }) {
                                    Text("帮助")
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (tab != null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    if (tab != item) {
                                        sound()
                                        nav.navigate(item.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(painterResource(item.icon), null) },
                                label = { Text(item.title, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { insets ->
            val duration = settings.motion.durationMillis
            NavHost(
                navController = nav,
                startDestination = Tab.HOME.route,
                modifier = Modifier.fillMaxSize().padding(insets),
                enterTransition = { if (duration == 0) EnterTransition.None else fadeIn(tween(duration)) },
                exitTransition = { if (duration == 0) ExitTransition.None else fadeOut(tween(duration / 2)) },
            ) {
                composable(Tab.HOME.route) {
                    HomePage(settings, onSearch = { nav.navigate(Tab.SEARCH.route) { launchSingleTop = true } }) {
                        openTool(it)
                    }
                }
                composable(Tab.CATEGORIES.route) {
                    CategoriesPage { nav.navigate("category/${it.name}") }
                }
                composable(CATEGORY) { entry ->
                    categoryFor(entry.arguments?.getString("category"))?.let { category ->
                        CategoryToolsPage(category) { openTool(it) }
                    }
                }
                composable(Tab.SEARCH.route) { SearchPage { openTool(it) } }
                composable(Tab.COLLECTIONS.route) {
                    CollectionsPage(settings, state) { nav.navigate("collection/$it") }
                }
                composable(Tab.SETTINGS.route) {
                    SettingsPage(settings, state, onRoute = { nav.navigate(it) })
                }
                composable("home-order") { HomeOrderPage(settings, state) }
                composable("palettes") { PalettePickerPage(settings.palette) { state.palette(it) } }
                composable("permissions") { PermissionsPage() }
                composable("about") { AboutPage() }
                composable("help") { HelpPage { nav.navigate("tool-help/$it") } }
                composable(COLLECTION) { entry ->
                    val collection = settings.collections.firstOrNull { it.id == entry.arguments?.getString("id") }
                    if (collection != null) CollectionPage(collection, state) { openTool(it) }
                }
                composable(
                    TOOL,
                    arguments = listOf(navArgument("input") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { backStack ->
                    val entry = backStack.arguments?.getString("toolId")?.let(ToolCatalog::find)
                    val input = remember(backStack) { state.takeToolInput(backStack.arguments?.getString("input")) }
                    if (entry?.module != null) {
                        Box(Modifier.fillMaxSize()) {
                            if (input is ToolInput.Media && entry.module is UriInputTool) {
                                entry.module.Content(input.uris)
                            } else {
                                entry.module.Content((input as? ToolInput.Text)?.value)
                            }
                        }
                    } else if (entry != null) PendingToolPage(entry)
                }
                composable(TOOL_HELP) { backStack ->
                    backStack.arguments?.getString("toolId")?.let(ToolCatalog::find)?.let {
                        ToolHelpPage(it)
                    }
                }
            }
        }

        val share = request as? LaunchRequest.SharedText
        PermissionSetup(canShow = share == null)
        if (share != null) {
            AlertDialog(
                onDismissRequest = state::clearRequest,
                title = { Text("打开共享文本") },
                text = { Text("选择记录内容，或在当前页面查看字数。") },
                confirmButton = {
                    TextButton(onClick = { state.clearRequest(); openTool(QUICK_NOTE_ID, ToolInput.Text(share.text)) }) {
                        Text("用即记记录")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state.clearRequest(); openTool(TEXT_STATS_ID, ToolInput.Text(share.text)) }) {
                        Text("统计字数")
                    }
                },
            )
        }
    }
}

private fun categoryFor(name: String?): ToolCategory? = ToolCategory.entries.firstOrNull { it.name == name }
