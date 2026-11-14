package com.xjyzs.dlmunews

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xjyzs.dlmunews.ui.theme.DLMUNewsTheme
import com.xjyzs.dlmunews.utils.AnimatedExpandDialog
import com.xjyzs.dlmunews.utils.MainViewModel
import com.xjyzs.dlmunews.utils.NoticeDetail
import com.xjyzs.dlmunews.utils.NoticeSummary
import com.xjyzs.dlmunews.utils.buildNoticeHtmlDocument
import com.xjyzs.dlmunews.utils.extractWebNewsId
import com.xjyzs.dlmunews.utils.fetchNoticeDetail
import com.xjyzs.dlmunews.utils.fetchNoticeList
import com.xjyzs.dlmunews.utils.fetchToken
import com.xjyzs.dlmunews.utils.getTokenFromServer
import com.xjyzs.dlmunews.utils.openExternalUrl
import com.xjyzs.dlmunews.utils.uploadToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PAGE_SIZE = 10

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            DLMUNewsTheme {
                MainUI(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainUI(viewModel: MainViewModel) {
    var addButtonRect by remember { mutableStateOf(Rect.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootDialogExpanded by remember { mutableStateOf(false) }
    var modifyExpanded by remember { mutableStateOf(false) }

    var id by remember { mutableStateOf("") }
    var expireTime by remember { mutableLongStateOf(0) }
    var clientType by remember { mutableIntStateOf(0) }
    var serverUrl by remember { mutableStateOf("") }
    var authorization by remember { mutableStateOf("") }
    var authReady by remember { mutableStateOf(false) }
    var loadTrigger by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val pref = remember { context.getSharedPreferences("main", Context.MODE_PRIVATE) }
    val formatterFull = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }

    fun getDetails(token: String) {
        try {
            val jsonBase64 = token.split(".")[1]
            val jsonStr = String(Base64.decode(jsonBase64, Base64.DEFAULT))
            val jsonObject = Gson().fromJson(jsonStr, JsonObject::class.java)
            id = jsonObject.get("jti").asString
            expireTime = jsonObject.get("exp").asLong
        } catch (_: Exception) {
            id = ""
            expireTime = 0
        }
    }

    fun refreshNotices(isPullRefresh: Boolean = false) {
        if (authorization.length <= 10) {
            viewModel.isLoading = false
            viewModel.isRefreshing = false
            viewModel.listError = "请点击右上角 + 按钮添加 authorization"
            return
        }
        if (viewModel.isLoading || viewModel.isRefreshing) return

        viewModel.viewModelScope.launch {
            if (isPullRefresh) {
                viewModel.isRefreshing = true
            } else {
                viewModel.isLoading = true
            }
            viewModel.listError = null
            viewModel.loadMoreError = null

            val result = fetchNoticeList(authorization, start = 0, size = PAGE_SIZE)
            result.onSuccess { list ->
                viewModel.notices.clear()
                viewModel.notices.addAll(list)
                viewModel.nextStart = list.size
                viewModel.hasMore = list.size >= PAGE_SIZE
                pref.edit {
                    putLong("lastRefresh", System.currentTimeMillis())
                }
            }.onFailure { err ->
                val message = err.message ?: "加载失败"
                if (viewModel.notices.isEmpty()) {
                    viewModel.listError = message
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            viewModel.isLoading = false
            viewModel.isRefreshing = false
        }
    }

    fun loadMoreNotices() {
        if (authorization.length <= 10) return
        if (viewModel.isLoading || viewModel.isRefreshing || viewModel.isLoadingMore || !viewModel.hasMore) {
            return
        }
        viewModel.viewModelScope.launch {
            viewModel.isLoadingMore = true
            viewModel.loadMoreError = null

            val result = fetchNoticeList(authorization, start = viewModel.nextStart, size = PAGE_SIZE)
            result.onSuccess { list ->
                val existingIds = viewModel.notices.mapTo(HashSet()) { it.id }
                val newItems = list.filter { it.id !in existingIds }
                viewModel.notices.addAll(newItems)
                viewModel.nextStart += list.size
                viewModel.hasMore = list.size >= PAGE_SIZE
            }.onFailure { err ->
                viewModel.loadMoreError = err.message ?: "加载更多失败"
            }

            viewModel.isLoadingMore = false
        }
    }

    fun loadNoticeDetail(notice: NoticeSummary) {
        viewModel.selectedNotice = notice
        viewModel.noticeDetail = null
        viewModel.detailError = null
        viewModel.isDetailLoading = true

        viewModel.viewModelScope.launch {
            val newsId = extractWebNewsId(notice)
            val result = fetchNoticeDetail(authorization, newsId)
            result.onSuccess { detail ->
                viewModel.noticeDetail = detail
            }.onFailure { err ->
                viewModel.detailError = err.message ?: "加载详情失败"
            }
            viewModel.isDetailLoading = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            clientType = pref.getInt("clientType", 0)
            serverUrl = pref.getString("serverUrl", "") ?: ""
        } catch (_: Exception) {
        }

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            authorization = pref.getString("authorization", "") ?: ""
            getDetails(authorization)
            if (System.currentTimeMillis() > expireTime * 1000) { // 已过期
                if (clientType == 2) {
                    val requestUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                    val serverAuthorization = getTokenFromServer(requestUrl)
                    if (serverAuthorization.length > 10) authorization = serverAuthorization
                } else {
                    try {
                        val newAuthorization = fetchToken(viewModel)
                        if (newAuthorization.length > 10) authorization = newAuthorization
                    } catch (e: Exception) {
                        if (e.message?.contains("denied") == true || e.message?.contains("run program \"su\"") == true) {
                            if (!pref.getBoolean("doNotShowRootDialog", false)) {
                                rootDialogExpanded = true
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context, e.message, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    viewModel.isLoading = false
                }
                if (authorization != (pref.getString("authorization", "") ?: "")) {
                    pref.edit {
                        putString("authorization", authorization)
                    }
                    if (clientType == 1) uploadToken(authorization, serverUrl)
                }
                getDetails(authorization)
            }
            if (authorization.length <= 10) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context, "请点击右上角 + 按钮添加 authorization", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            authReady = true
            loadTrigger++
        }
    }

    LaunchedEffect(loadTrigger) {
        if (!authReady) return@LaunchedEffect
        refreshNotices(isPullRefresh = false)
    }

    BackHandler(enabled = viewModel.selectedNotice != null && !modifyExpanded && !rootDialogExpanded) {
        viewModel.selectedNotice = null
        viewModel.noticeDetail = null
        viewModel.detailError = null
        viewModel.isDetailLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootSize = it.size }
    ) {
        AnimatedContent(
            targetState = viewModel.selectedNotice,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "NoticeScreenTransition"
        ) { selectedNotice ->
            if (selectedNotice == null) {
                NoticeListScreen(
                    viewModel = viewModel,
                    onRefresh = { isPull -> refreshNotices(isPullRefresh = isPull) },
                    onLoadMore = { loadMoreNotices() },
                    onNoticeClick = { notice -> loadNoticeDetail(notice) },
                    onOpenAuthDialog = { modifyExpanded = true },
                    onAuthButtonPositioned = { rect -> addButtonRect = rect }
                )
            } else {
                NoticeDetailScreen(
                    notice = selectedNotice,
                    detail = viewModel.noticeDetail,
                    isLoading = viewModel.isDetailLoading,
                    error = viewModel.detailError,
                    onBack = {
                        viewModel.selectedNotice = null
                        viewModel.noticeDetail = null
                        viewModel.detailError = null
                        viewModel.isDetailLoading = false
                    },
                    onRetry = { loadNoticeDetail(selectedNotice) }
                )
            }
        }

        if (rootDialogExpanded) {
            AlertDialog(
                onDismissRequest = { rootDialogExpanded = false },
                title = { Text("「推荐」从海大在线自动获取 authorization") },
                text = {
                    Column {
                        Text("该功能需要 root 权限")
                        Text("需要在 3 天内成功登录过海大在线")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pref.edit {
                            putBoolean("doNotShowRootDialog", true)
                        }
                        rootDialogExpanded = false
                    }) { Text("不再提示") }
                }
            )
        }

        AnimatedExpandDialog(
            showDialog = modifyExpanded,
            buttonRect = addButtonRect,
            rootSize = rootSize,
            onDismissRequest = { modifyExpanded = false },
            titleText = "修改 authorization",
            text = {
                Column {
                    OutlinedTextField(
                        value = authorization,
                        onValueChange = {
                            authorization = it
                            getDetails(authorization)
                        },
                        placeholder = { Text("Bearer") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    val options = listOf("None", "发送端", "接收端")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, label ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size
                                ),
                                onClick = {
                                    if (clientType != index) {
                                        clientType = index
                                        pref.edit(commit = true) {
                                            putInt("clientType", clientType)
                                        }
                                    }
                                },
                                selected = index == clientType,
                                label = { Text(label) }
                            )
                        }
                    }
                    if (clientType != 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {
                                serverUrl = it
                                pref.edit {
                                    putString("serverUrl", serverUrl)
                                }
                            },
                            placeholder = { Text("https://example.com/exp/") },
                            singleLine = true
                        )
                    }
                    val lastRefreshTime = pref.getLong("lastRefresh", 0)
                    if (lastRefreshTime > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "上次刷新: ${formatterFull.format(Instant.ofEpochMilli(lastRefreshTime))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (id.isNotEmpty()) {
                        Text(
                            "账号: $id",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (expireTime > 0) {
                        Text(
                            "失效时间: ${formatterFull.format(Instant.ofEpochMilli(expireTime * 1000))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pref.edit {
                        putString("authorization", authorization)
                    }
                    modifyExpanded = false
                    loadTrigger++
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { modifyExpanded = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticeListScreen(
    viewModel: MainViewModel,
    onRefresh: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    onNoticeClick: (NoticeSummary) -> Unit,
    onOpenAuthDialog: () -> Unit,
    onAuthButtonPositioned: (Rect) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(
        listState,
        viewModel.notices.size,
        viewModel.hasMore,
        viewModel.isLoading,
        viewModel.isRefreshing,
        viewModel.isLoadingMore,
        viewModel.loadMoreError
    ) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val count = viewModel.notices.size
                if (lastVisible != null &&
                    count > 0 &&
                    lastVisible >= count - 2 &&
                    viewModel.hasMore &&
                    !viewModel.isLoading &&
                    !viewModel.isRefreshing &&
                    !viewModel.isLoadingMore &&
                    viewModel.loadMoreError == null
                ) {
                    onLoadMore()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("海大公告") },
                actions = {
                    IconButton(onClick = { onRefresh(false) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = onOpenAuthDialog,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            onAuthButtonPositioned(coordinates.boundsInRoot())
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "修改 authorization")
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = viewModel.isRefreshing,
            onRefresh = { onRefresh(true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                viewModel.isLoading && viewModel.notices.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                viewModel.notices.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = viewModel.listError ?: "暂无公告",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { onRefresh(false) }) {
                                Text("重试")
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(viewModel.notices, key = { it.id }) { notice ->
                            NoticeItemCard(
                                notice = notice,
                                onClick = { onNoticeClick(notice) }
                            )
                        }

                        item(key = "__footer__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    viewModel.isLoadingMore -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                "正在加载更多...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    viewModel.loadMoreError != null -> {
                                        TextButton(onClick = onLoadMore) {
                                            Text("${viewModel.loadMoreError}，点击重试")
                                        }
                                    }

                                    !viewModel.hasMore -> {
                                        Text(
                                            "没有更多公告了",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeItemCard(
    notice: NoticeSummary,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = notice.publishDeptName.ifBlank { "学校公告" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = notice.publishDate,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticeDetailScreen(
    notice: NoticeSummary,
    detail: NoticeDetail?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val externalUrl = detail?.redirect?.takeIf { it.isNotBlank() } ?: notice.url
    val isDark = isSystemInDarkTheme()
    val textColor = MaterialTheme.colorScheme.onBackground
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = {
                    Text(
                        text = "公告详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (externalUrl.isNotBlank()) {
                        IconButton(onClick = { openExternalUrl(context, externalUrl) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "在浏览器打开"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onRetry) {
                                Text("重试")
                            }
                        }
                    }
                }

                detail != null -> {
                    val htmlDoc = remember(detail, isDark, textColor, mutedColor, linkColor, dividerColor) {
                        buildNoticeHtmlDocument(
                            detail = detail,
                            textColor = textColor,
                            mutedColor = mutedColor,
                            linkColor = linkColor,
                            dividerColor = dividerColor,
                            isDarkTheme = isDark
                        )
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    settings.apply {
                                        javaScriptEnabled = false
                                        defaultTextEncodingName = "UTF-8"
                                        loadWithOverviewMode = true
                                        useWideViewPort = false
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val target = request?.url?.toString() ?: return false
                                            openExternalUrl(ctx, target)
                                            return true
                                        }
                                    }
                                }
                            },
                            update = { webView ->
                                if (webView.tag != htmlDoc) {
                                    webView.tag = htmlDoc
                                    webView.loadDataWithBaseURL(
                                        "https://info.dlmu.edu.cn/",
                                        htmlDoc,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }
                        )

                        if (detail.redirect.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(context, detail.redirect) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("打开跳转链接")
                                }
                            }
                        }

                        if (detail.files.isNotEmpty()) {
                            HorizontalDivider()
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "附件 (${detail.files.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    detail.files.forEach { file ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    openExternalUrl(context, file.url)
                                                }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AttachFile,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = file.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
