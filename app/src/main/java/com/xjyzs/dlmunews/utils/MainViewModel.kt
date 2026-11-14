package com.xjyzs.dlmunews.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class NoticeSummary(
    val id: String,
    val title: String,
    val publishDeptName: String,
    val publishDeptId: String,
    val publishDate: String,
    val url: String
)

data class NoticeFile(
    val name: String,
    val url: String
)

data class NoticeDetail(
    val id: String,
    val title: String,
    val publishUserName: String,
    val publishDeptName: String,
    val publishDate: String,
    val click: Int,
    val content: String,
    val redirect: String,
    val type: String,
    val files: List<NoticeFile>
)

class MainViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var nextStart by mutableIntStateOf(0)
    var listError by mutableStateOf<String?>(null)
    var loadMoreError by mutableStateOf<String?>(null)
    val notices = mutableStateListOf<NoticeSummary>()

    var selectedNotice by mutableStateOf<NoticeSummary?>(null)
    var noticeDetail by mutableStateOf<NoticeDetail?>(null)
    var isDetailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)
}
