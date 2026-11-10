package net.pocketnai.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import net.pocketnai.ui.motion.ImageMotionRoot
import net.pocketnai.ui.motion.ImageMotionViewport
import net.pocketnai.ui.motion.ImageMotionRoute
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.imagePageEnter
import net.pocketnai.ui.motion.imagePageExit
import net.pocketnai.ui.motion.imageMotionChrome
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import net.pocketnai.ui.artistlab.ArtistLabScreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import net.pocketnai.ui.chat.ChatScreen
import net.pocketnai.ui.chat.ChatViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.ui.detail.DetailScreen
import net.pocketnai.ui.inpaint.InpaintEditorScreen
import net.pocketnai.ui.inpaint.InpaintPreparing
import net.pocketnai.ui.inpaint.InpaintUnavailable
import net.pocketnai.ui.home.HomeScreen
import net.pocketnai.ui.settings.SettingsScreen
import net.pocketnai.ui.update.UpdateAvailableDialog
import net.pocketnai.ui.update.UpdateViewModel
import java.io.File

/** 导航目的地。用常量而不是字符串字面量，避免路由名拼错只在运行时才发现。 */
object Routes {
    const val CONNECT = "connect"

    /** 首页 = 画廊 + 生成悬浮层（两者已合并）。 */
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ANLAS_STATISTICS = "anlas-statistics"
    const val CHAT = "chat"
    const val CHAT_SETTINGS = "chat-settings"
    const val ARTIST_LAB = "artist-lab"
    const val DETAIL = "detail/{imageId}"
    const val REFERENCE_DETAIL = "reference-detail/{imageId}"

    /** 局部重绘的蒙版编辑器（全屏）。 */
    const val INPAINT = "inpaint"

    fun detail(imageId: String): String = "detail/$imageId"
    fun referenceDetail(imageId: String): String = "reference-detail/$imageId"

    /** 底部导航展示的 Tab。 */
    val tabs = listOf(HOME, CHAT, ARTIST_LAB, SETTINGS)
}

private data class TabSpec(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PocketNaiApp(sharedImageUri: String? = null, onSharedImageHandled: () -> Unit = {}) {
    net.pocketnai.ui.safety.SfwDisplayProvider {
        ImageMotionRoot { PocketNaiAppContent(sharedImageUri, onSharedImageHandled) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PocketNaiAppContent(sharedImageUri: String?, onSharedImageHandled: () -> Unit) {
    val imageMotion = LocalImageMotion.current!!
    val container = LocalAppContainer.current
    val connected by container.sessionState.connected.collectAsStateWithLifecycle()
    val credentialType by container.sessionState.credentialType.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // 生成状态提升到这一层：底部固定栏和悬浮层必须操作同一个 ViewModel，
    // 否则按钮改了参数、悬浮层却看不到。
    val generateViewModel = remember(container) { container.generateViewModel }
    val generateState by generateViewModel.state.collectAsStateWithLifecycle()
    val openAiViewModel = remember(container) { container.openAiImageViewModel }
    val provider by container.openAiImageSettings.provider.collectAsStateWithLifecycle()
    val openAiMode = provider == net.pocketnai.domain.openai.ImageProvider.OPEN_AI
    val chatEnabled by container.settingsStore.chatEnabled.collectAsStateWithLifecycle()
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer {
            ChatViewModel(container.llmSettingsStore, container.chatClient, container.chatStore, container.agentFiles,
                object : net.pocketnai.domain.chat.ChatImageGenerator {
                    override fun currentParams() = generateViewModel.state.value.params
                    override fun promptSnapshot() = if (container.openAiImageSettings.provider.value == net.pocketnai.domain.openai.ImageProvider.OPEN_AI)
                        openAiViewModel.promptSnapshot() else generateViewModel.promptSnapshot()
                    override fun applyPromptEdit(edit: net.pocketnai.domain.chat.PromptEdit): Int? {
                        val isOpenAi = container.openAiImageSettings.provider.value == net.pocketnai.domain.openai.ImageProvider.OPEN_AI
                        if ((edit.provider == "openai") != isOpenAi) return null
                        return if (isOpenAi) openAiViewModel.applyPromptEdit(edit) else generateViewModel.applyPromptEdit(edit)
                    }
                    override fun undoPromptEdit(provider: String, revision: Int) = if (provider == "openai")
                        openAiViewModel.undoPromptEdit(revision) else generateViewModel.undoPromptEdit(revision)
                    override suspend fun prepare(params: List<net.pocketnai.domain.model.GenerationParams>): List<net.pocketnai.domain.model.GenerationParams> {
                        val macros = container.promptFavoriteRepository.snapshot()
                        return params.map { net.pocketnai.domain.prompt.PromptTemplates.resolve(it, macros, kotlin.random.Random.Default) }
                    }
                    override fun authorizationKey() = generateViewModel.artistLabAccount()
                    override suspend fun quote(params: net.pocketnai.domain.model.GenerationParams): net.pocketnai.domain.chat.ChatImageQuote {
                        if (container.openAiImageSettings.provider.value == net.pocketnai.domain.openai.ImageProvider.OPEN_AI)
                            throw net.pocketnai.domain.chat.ChatFailure("OpenAI 图片暂时请在画廊的专用生成面板创建")
                        return generateViewModel.quoteForChat(params)
                    }
                    override suspend fun generate(params: net.pocketnai.domain.model.GenerationParams, account: String?, beforeSend: suspend () -> Unit, onImage: (net.pocketnai.domain.chat.ChatImage) -> Unit): List<net.pocketnai.domain.chat.ChatImage> {
                        if (container.openAiImageSettings.provider.value == net.pocketnai.domain.openai.ImageProvider.OPEN_AI)
                            throw net.pocketnai.domain.chat.ChatFailure("OpenAI 图片暂时请在画廊的专用生成面板创建")
                        return generateViewModel.generateForChat(params, onImage, account, beforeSend)
                    }
                }, container.settingsStore.chatEnabled, container.chatAttachmentStore)
        }
    })
    val artistLabConfigured by container.settingsStore.artistLabEnabled.collectAsStateWithLifecycle()
    val artistLabEnabled = artistLabConfigured && !openAiMode
    val artistLabViewModel = remember(container) { container.artistLabViewModel }
    LaunchedEffect(sharedImageUri, generateState.referenceBusy) {
        if (sharedImageUri != null && !generateState.referenceBusy) {
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
            if (openAiMode) openAiViewModel.addReference(ReferenceSource.PickedUri(sharedImageUri))
            else generateViewModel.onReferencePicked(ReferenceSource.PickedUri(sharedImageUri))
            onSharedImageHandled()
        }
    }

    // 更新检查独立于生成链路：它只读 GitHub 上的 Release。
    // 同样提升到这一层：启动时的那次检查与设置页的手动检查必须共用一份状态。
    val updateViewModel: UpdateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                UpdateViewModel(
                    api = container.githubReleaseApi,
                    downloader = container.updateDownloader,
                    settingsStore = container.settingsStore,
                )
            }
        },
    )
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // 启动后静默查一次（失败不打扰；对同一个构建点过"稍后"的不再弹）。
    LaunchedEffect(Unit) {
        updateViewModel.checkOnLaunch()
    }

    // 下载好的 APK 由界面调起系统安装器：需要 Activity context 与 FileProvider，
    // ViewModel 不碰 Intent。
    val context = LocalContext.current
    var installPermissionHint by remember { mutableStateOf(false) }
    LaunchedEffect(updateState.readyApk) {
        val apk = updateState.readyApk ?: return@LaunchedEffect
        if (context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(installApkIntent(context, apk)) }
        } else {
            // 没有"安装未知应用"授权：先跳去授权页。文件已缓存在本地，
            // 用户授权回来后再点一次"下载并安装"即可（不会重新下载）。
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri(),
                    ),
                )
            }
            installPermissionHint = true
        }
        updateViewModel.onInstallHandled()
    }

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
    val themeReveal = net.pocketnai.ui.theme.LocalThemeReveal.current
    LaunchedEffect(backStackEntry?.id) { themeReveal?.cancelVisual() }
    val currentRoute = backStackEntry?.destination?.route
    LaunchedEffect(chatEnabled, currentRoute) {
        if (!chatEnabled && currentRoute == Routes.CHAT) navController.navigateToTab(Routes.HOME)
    }

    LaunchedEffect(artistLabEnabled, currentRoute) {
        if (!artistLabEnabled && currentRoute == Routes.ARTIST_LAB) navController.navigateToTab(Routes.HOME)
    }

    val referencePreviews = remember { mutableMapOf<String, net.pocketnai.ui.motion.ImageOriginPreview>() }
    // Each destination owns its insets and navigation chrome, including while it leaves.
    Surface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(net.pocketnai.ui.generate.LocalReferenceOriginOpener provides { preview ->
            navController.navigate(Routes.referenceDetail(preview.imageId))
            navController.currentBackStackEntry?.id?.let { referencePreviews[it] = preview }
        }) {
        NavHost(
            navController = navController,
            startDestination = if (connected) Routes.HOME else Routes.CONNECT,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                when (targetState.destination.route) {
                    Routes.DETAIL, Routes.REFERENCE_DETAIL -> imagePageEnter()
                    Routes.INPAINT -> fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 5 }
                    else -> if (initialState.destination.route in listOf(Routes.DETAIL, Routes.REFERENCE_DETAIL)) imagePageEnter() else fadeIn(tween(160))
                }
            },
            exitTransition = {
                if (targetState.destination.route in listOf(Routes.DETAIL, Routes.REFERENCE_DETAIL) ||
                    initialState.destination.route in listOf(Routes.DETAIL, Routes.REFERENCE_DETAIL))
                    imagePageExit() else fadeOut(tween(100))
            },
            popEnterTransition = {
                when (initialState.destination.route) {
                    Routes.DETAIL, Routes.REFERENCE_DETAIL -> imagePageEnter()
                    Routes.ANLAS_STATISTICS, Routes.CHAT_SETTINGS, Routes.INPAINT -> fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.96f)
                    else -> fadeIn(tween(160))
                }
            },
            popExitTransition = { when (initialState.destination.route) {
                Routes.DETAIL, Routes.REFERENCE_DETAIL -> imagePageExit()
                Routes.INPAINT -> fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.94f) + slideOutHorizontally(tween(260)) { it / 4 }
                else -> fadeOut(tween(100))
            } },
        ) {
            composable(Routes.CONNECT) {
                ImageMotionRoute(this, Routes.CONNECT) {
                    PocketNaiPageFrame(navController, Routes.CONNECT, chatEnabled, artistLabEnabled) {
                        ConnectScreen(
                            onConnected = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.CONNECT) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
            composable(Routes.HOME) {
                ImageMotionRoute(this, Routes.HOME) {
                    PocketNaiPageFrame(navController, Routes.HOME, chatEnabled, artistLabEnabled) {
                        HomeScreen(
                            generateViewModel = generateViewModel,
                            state = generateState,
                            connected = connected,
                            credentialType = credentialType,
                            onOpenImage = { imageId -> imageMotion.open(Routes.HOME, imageId); navController.navigate(Routes.detail(imageId)) },
                            onInpaintImage = { item ->
                                if (openAiMode) openAiViewModel.addReference(ReferenceSource.LocalPath(item.relativePath))
                                else {
                                    generateViewModel.onInpaintBasePicked(ReferenceSource.LocalPath(item.relativePath))
                                    navController.navigate(Routes.INPAINT) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onRequestConnect = { navController.navigate(Routes.CONNECT) },
                            onOpenInpaintEditor = { navController.navigate(Routes.INPAINT) },
                            openAiViewModel = openAiViewModel.takeIf { openAiMode },
                            onOpenAiSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenStatistics = { navController.navigate(Routes.ANLAS_STATISTICS) { launchSingleTop = true } },
                        )
                    }
                }
            }
            composable(Routes.SETTINGS) {
                ImageMotionRoute(this, Routes.SETTINGS) {
                    PocketNaiPageFrame(navController, Routes.SETTINGS, chatEnabled, artistLabEnabled) {
                        SettingsScreen(
                            onRequestConnect = { navController.navigate(Routes.CONNECT) },
                            onOpenStatistics = { navController.navigate(Routes.ANLAS_STATISTICS) { launchSingleTop = true } },
                            updateViewModel = updateViewModel,
                            onOpenChatSettings = { navController.navigate(Routes.CHAT_SETTINGS) { launchSingleTop = true } },
                        )
                    }
                }
            }
            // Navigation Compose 2.8 drives these seekable transitions during predictive back,
            // including gesture cancellation. Keep one back-stack owner instead of intercepting it.
            composable(
                Routes.ANLAS_STATISTICS,
                enterTransition = { fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 5 } },
                popExitTransition = { fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.94f) +
                    slideOutHorizontally(tween(260)) { it / 4 } },
            ) {
                ImageMotionRoute(this, Routes.ANLAS_STATISTICS) {
                    net.pocketnai.ui.billing.AnlasStatisticsScreen(
                        repository = container.anlasLedgerRepository,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Routes.CHAT_SETTINGS,
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(300)) { it / 7 } },
                popExitTransition = { fadeOut(tween(220)) + scaleOut(tween(300), targetScale = .96f) +
                    slideOutHorizontally(tween(300)) { it / 5 } },
            ) {
                net.pocketnai.ui.chat.ChatSettingsScreen(chatViewModel) { navController.popBackStack() }
            }
            composable(Routes.CHAT) {
                ImageMotionRoute(this, Routes.CHAT) {
                    PocketNaiPageFrame(navController, Routes.CHAT, chatEnabled, artistLabEnabled) {
                        ChatScreen(chatViewModel, container.fileStore::resolve) { imageId -> imageMotion.open(Routes.CHAT, imageId); navController.navigate(Routes.detail(imageId)) }
                    }
                }
            }
            composable(Routes.ARTIST_LAB) {
                ImageMotionRoute(this, Routes.ARTIST_LAB) {
                    PocketNaiPageFrame(navController, Routes.ARTIST_LAB, chatEnabled, artistLabEnabled) {
                        ArtistLabScreen(artistLabViewModel, generateState.params, container.fileStore::resolve) { imageId ->
                            imageMotion.open(Routes.ARTIST_LAB, imageId)
                            navController.navigate(Routes.detail(imageId))
                        }
                    }
                }
            }
            listOf(Routes.DETAIL, Routes.REFERENCE_DETAIL).forEach { detailRoute ->
            composable(detailRoute) { entry ->
                val detailMotion = if (detailRoute == Routes.REFERENCE_DETAIL) remember(entry) {
                    net.pocketnai.ui.motion.ImageMotionState().apply {
                        val id = entry.arguments?.getString("imageId").orEmpty()
                        preview = referencePreviews.remove(entry.id)
                        open("reference-origin", id)
                    }
                } else imageMotion
                CompositionLocalProvider(LocalImageMotion provides detailMotion) {
                ImageMotionRoute(this, detailMotion.source ?: detailRoute) {
                    PocketNaiPageFrame(navController, Routes.DETAIL, chatEnabled, artistLabEnabled) {
                        DetailScreen(
                            imageId = entry.arguments?.getString("imageId").orEmpty(),
                            onBack = { detailMotion.returnToSource(); navController.popBackStack() },
                            onParamsReused = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            },
                            onAskAssistant = if (chatEnabled) ({ path, id ->
                                chatViewModel.addGalleryImage(path, id)
                                navController.navigateToTab(Routes.CHAT)
                            }) else null,
                            onInpaint = { relativePath ->
                                // 详情页 → 以当前这张图作为重绘底图并进入编辑器。
                                generateViewModel.onInpaintBasePicked(ReferenceSource.LocalPath(relativePath))
                                navController.navigate(Routes.INPAINT) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            }
            }
            }
            composable(Routes.INPAINT) {
                ImageMotionRoute(this, Routes.INPAINT) {
                    PocketNaiPageFrame(navController, Routes.INPAINT, chatEnabled, artistLabEnabled) {
                        // 底图是**异步导入**的：进入这个路由时它可能还在处理中，
                        // 因此要显式区分"正在准备 / 失败 / 就绪"三种状态，
                        // 不能因为一次读不到就弹回首页（真机验证时正是这么错的）。
                        val base = generateState.referenceSource
                        when {
                            base != null -> InpaintEditorScreen(
                                viewModel = generateViewModel,
                                base = base,
                                onDone = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                            )

                            generateState.referenceError != null -> InpaintUnavailable(
                                messageRes = generateState.referenceError!!.code,
                                onBack = { navController.popBackStack() },
                            )

                            else -> InpaintPreparing(onCancel = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
        }
    }

    // "发现新版本"对话框：待下载 / 下载中 / 出错重试三种形态共用一个框。
    updateState.available?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersionName = updateViewModel.currentVersionName,
            download = updateState.download,
            errorCode = updateState.error,
            onDownload = updateViewModel::downloadUpdate,
            onLater = updateViewModel::dismissAvailable,
            onDismissError = updateViewModel::dismissError,
        )
    }

    // 需要"安装未知应用"授权时的一次性说明（从系统设置页回来时能看到）。
    if (installPermissionHint) {
        AlertDialog(
            onDismissRequest = { installPermissionHint = false },
            title = { Text(stringResource(R.string.update_title)) },
            text = { Text(stringResource(R.string.update_install_permission_hint)) },
            confirmButton = {
                TextButton(onClick = { installPermissionHint = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }
}

/** Route-owned chrome cannot resize the outgoing gallery when another destination opens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PocketNaiPageFrame(
    navController: NavHostController,
    route: String,
    chatEnabled: Boolean,
    artistLabEnabled: Boolean,
    keyboardInsets: WindowInsets = WindowInsets.ime,
    content: @Composable () -> Unit,
) {
    val keyboardPage = route == Routes.CHAT || route == Routes.ARTIST_LAB
    val ime = if (keyboardPage) keyboardInsets else WindowInsets(0, 0, 0, 0)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail && route in Routes.tabs) PocketNaiNavRail(navController, route, chatEnabled, artistLabEnabled)
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!rail && route in Routes.tabs) {
                        // Its layout height stays stable even while the keyboard covers it.
                        // Only draw the exposed strip; no IME flag removes/recreates this slot.
                        Box(Modifier.imageMotionChrome(zIndex = 3f).drawWithContent {
                            val exposedHeight = (size.height - ime.getBottom(density)).coerceAtLeast(0f)
                            if (exposedHeight > 0f) clipRect(bottom = exposedHeight) { this@drawWithContent.drawContent() }
                        }) {
                            PocketNaiBottomBar(navController, route, chatEnabled, artistLabEnabled)
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    // Consume the stable navigation height first. IME padding adds only the
                    // remainder: content avoidance is max(navigation height, keyboard height).
                    ImageMotionViewport(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                        .then(if (keyboardPage) Modifier.windowInsetsPadding(ime) else Modifier)) { content() }
                    Spacer(Modifier.fillMaxWidth().height(padding.calculateTopPadding()).align(Alignment.TopCenter)
                        .imageMotionChrome(zIndex = 4f).background(MaterialTheme.colorScheme.background))
                }
            }
        }
    }
}

@Composable
private fun PocketNaiBottomBar(
    navController: NavHostController,
    currentRoute: String?,
    chatEnabled: Boolean,
    artistLabEnabled: Boolean,
) {
    val specs = tabSpecs(chatEnabled, artistLabEnabled)

    NavigationBar(modifier = Modifier.testTag("bottom-navigation")) {
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

/** 宽屏形态下的左侧导航栏：与底部栏同样的两个 Tab，只是挪到左边。 */
@Composable
private fun PocketNaiNavRail(
    navController: NavHostController,
    currentRoute: String?,
    chatEnabled: Boolean,
    artistLabEnabled: Boolean,
) {
    val specs = tabSpecs(chatEnabled, artistLabEnabled)

    NavigationRail(modifier = Modifier.imageMotionChrome(zIndex = 3f)) {
        specs.forEach { spec ->
            NavigationRailItem(
                selected = currentRoute == spec.route,
                onClick = { navController.navigateToTab(spec.route) },
                icon = { Icon(spec.icon, contentDescription = null) },
                label = { Text(stringResource(spec.labelRes)) },
            )
        }
    }
}

private fun tabSpecs(chatEnabled: Boolean, artistLabEnabled: Boolean) = buildList {
    add(TabSpec(Routes.HOME, Icons.Default.Image, R.string.nav_gallery))
    if (chatEnabled) add(TabSpec(Routes.CHAT, Icons.AutoMirrored.Filled.Chat, R.string.nav_chat))
    if (artistLabEnabled) add(TabSpec(Routes.ARTIST_LAB, Icons.Default.Casino, R.string.nav_artist_lab))
    add(TabSpec(Routes.SETTINGS, Icons.Default.Settings, R.string.nav_settings))
}

/** 切换 Tab 时保留各 Tab 自己的状态，并且不在返回栈里堆积重复条目。 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 交给系统安装器的 Intent：更新包在 cache 目录，必须经 FileProvider 授权读取。 */
private fun installApkIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
