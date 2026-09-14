package net.pocketnai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.pocketnai.R
import net.pocketnai.ui.connect.ConnectScreen
import net.pocketnai.ui.detail.DetailScreen
import net.pocketnai.ui.generate.GenerateViewModel
import net.pocketnai.ui.home.HomeScreen
import net.pocketnai.ui.settings.SettingsScreen

/** 导航目的地。用常量而不是字符串字面量，避免路由名拼错只在运行时才发现。 */
object Routes {
    const val CONNECT = "connect"

    /** 首页 = 画廊 + 生成悬浮层（两者已合并）。 */
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{imageId}"

    fun detail(imageId: String): String = "detail/$imageId"

    /** 底部导航展示的 Tab。 */
    val tabs = listOf(HOME, SETTINGS)
}

private data class TabSpec(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

@Composable
fun PocketNaiApp() {
    val container = LocalAppContainer.current
    val connected by container.sessionState.connected.collectAsStateWithLifecycle()
    val credentialType by container.sessionState.credentialType.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // 生成状态提升到这一层：底部固定栏和悬浮层必须操作同一个 ViewModel，
    // 否则按钮改了参数、悬浮层却看不到。
    val generateViewModel: GenerateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GenerateViewModel(
                    repository = container.generationRepository,
                    draftStore = container.draftStore,
                    draftPreferences = container.generationDraftPreferences,
                    tagSuggestionSource = container.tagSuggestionSource,
                    referenceImporter = container.referenceImageProcessor,
                    accountBalanceRepository = container.accountBalanceRepository,
                    costCalculator = container.anlasCostCalculator,
                )
            }
        },
    )
    val generateState by generateViewModel.state.collectAsStateWithLifecycle()

    // 余额是账户级状态，跟着"是否已连接"走：
    // 连上就刷新一次，断开就把内存里的余额清掉（绝不把上一个账号的余额留给下一个账号）。
    LaunchedEffect(connected) {
        if (connected) {
            generateViewModel.refreshBalanceOnForeground()
        } else {
            container.accountBalanceRepository.clear()
        }
    }

    // 回到前台时刷新一次；仓库内部会判断缓存是否还新鲜，不会反复打接口。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                generateViewModel.refreshBalanceOnForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    Scaffold(
        bottomBar = {
            // 底部只剩导航栏。生成按钮在首页生成悬浮层的头部里（GenerateButton），
            // 不再占用任何一条独立的底部栏。
            if (currentRoute in Routes.tabs) {
                PocketNaiBottomBar(
                    navController = navController,
                    currentRoute = currentRoute,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (connected) Routes.HOME else Routes.CONNECT,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    onConnected = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    generateViewModel = generateViewModel,
                    state = generateState,
                    connected = connected,
                    credentialType = credentialType,
                    onOpenImage = { imageId -> navController.navigate(Routes.detail(imageId)) },
                    onRequestConnect = { navController.navigate(Routes.CONNECT) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onRequestConnect = { navController.navigate(Routes.CONNECT) },
                )
            }
            composable(Routes.DETAIL) { entry ->
                DetailScreen(
                    imageId = entry.arguments?.getString("imageId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onParamsReused = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PocketNaiBottomBar(
    navController: NavHostController,
    currentRoute: String?,
) {
    val specs = listOf(
        TabSpec(Routes.HOME, Icons.Default.Image, R.string.nav_gallery),
        TabSpec(Routes.SETTINGS, Icons.Default.Settings, R.string.nav_settings),
    )

    NavigationBar {
        specs.forEach { spec ->
            NavigationBarItem(
                selected = currentRoute == spec.route,
                onClick = { navController.navigateToTab(spec.route) },
                icon = { Icon(spec.icon, contentDescription = null) },
                label = { Text(stringResource(spec.labelRes)) },
            )
        }
    }
}

/** 切换 Tab 时保留各 Tab 自己的状态，并且不在返回栈里堆积重复条目。 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
