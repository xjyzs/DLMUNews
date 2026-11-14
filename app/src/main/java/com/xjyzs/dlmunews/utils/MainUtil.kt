package com.xjyzs.dlmunews.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader

private val httpClient by lazy { OkHttpClient() }
private val gson by lazy { Gson() }

fun fetchToken(viewModel: MainViewModel): String {
    viewModel.isLoading = true
    val process = ProcessBuilder("su", "-M").start()
    val outputStream = process.outputStream
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    outputStream.write("ls -t /data/user/0/com.tencent.mm/files/mmkv/; echo __END__\n".toByteArray())
    outputStream.flush()
    val files = reader.lineSequence().takeWhile { it != "__END__" }
        .filter { it.startsWith("AppBrandMMKVStorage") && !it.endsWith("crc") }.toList()
    if (files.isEmpty()) {
        throw Exception("请启动海大在线")
    }
    val re =
        Regex("""https://wx.dlmu.edu.cn/weapp/redirect\?clientid=.*?&token=(?<token>.*?)&go=https""")
    var token: String
    for (i in files) {
        outputStream.write("cat \"/data/user/0/com.tencent.mm/files/mmkv/$i\"; echo __END__\n".toByteArray())
        outputStream.flush()
        val str = readBytesUntil(process.inputStream, "__END__").toString(Charsets.US_ASCII)
        val matchResult = re.findAll(str)
        for (j in matchResult) {
            token = j.groups["token"]?.value ?: ""
            val jsonBase64 = token.split(".")[1]
            val jsonStr = String(Base64.decode(jsonBase64, Base64.DEFAULT))
            val jsonObject = gson.fromJson(jsonStr, JsonObject::class.java)
            val expireTime = jsonObject.get("exp").asLong
            if (expireTime > System.currentTimeMillis() / 1000) {
                return "Bearer $token"
            }
        }
    }
    return "Bearer "
}

private fun readBytesUntil(ins: InputStream, marker: String): ByteArray {
    val markerBytes = marker.toByteArray()
    val mLen = markerBytes.size
    val buffer = ByteArrayOutputStream()
    val window = ArrayDeque<Byte>(mLen)

    while (true) {
        val b = ins.read()
        if (b == -1) break

        buffer.write(b)
        window.addLast(b.toByte())
        if (window.size > mLen) window.removeFirst()
        if (window.size == mLen) {
            var matched = true
            var i = 0
            for (wb in window) {
                if (wb != markerBytes[i]) {
                    matched = false
                    break
                }
                i++
            }
            if (matched) {
                val arr = buffer.toByteArray()
                return arr.copyOf(arr.size - mLen)
            }
        }
    }
    return buffer.toByteArray()
}

suspend fun uploadToken(authorization: String, serverUrl: String) {
    withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder().add("str", authorization).build()
        val request = Request.Builder().url(serverUrl + "upload").post(formBody).build()
        try {
            httpClient.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }
}

suspend fun getTokenFromServer(serverUrl: String): String = withContext(Dispatchers.IO) {
    val requestUrl = serverUrl + "source"
    val request = Request.Builder().url(requestUrl).get().build()

    try {
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body.string()
                Log.d("DLMUNews", "Successfully fetched token")
                body
            } else {
                ""
            }
        }
    } catch (e: Exception) {
        Log.e("DLMUNews", "Error fetching token", e)
        ""
    }
}

private fun buildDlmuGetRequest(url: String, authorization: String): Request {
    return Request.Builder()
        .url(url)
        .get()
        .addHeader(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 XWEB/1160117 MMWEBSDK/20250201 MMWEBID/8832 MicroMessenger/8.0.60.2840(0x28003C40) WeChat/arm64 Weixin GPVersion/1 NetType/WIFI Language/zh_CN ABI/arm64 MiniProgramEnv/android"
        )
        .addHeader("authorization", authorization)
        .addHeader("charset", "utf-8")
        .addHeader("x-client-version", "1.4.18")
        .addHeader("content-type", "application/json")
        .addHeader(
            "Referer",
            "https://servicewechat.com/wx3bf7324a6ede01d3/86/page-frame.html"
        )
        .build()
}

private fun JsonObject.optString(key: String, default: String = ""): String {
    val elem = get(key)
    return if (elem != null && !elem.isJsonNull) elem.asString else default
}

private fun JsonObject.optInt(key: String, default: Int = 0): Int {
    val elem = get(key)
    return if (elem != null && !elem.isJsonNull) elem.asInt else default
}

suspend fun fetchNoticeList(
    authorization: String,
    start: Int,
    size: Int = 10
): Result<List<NoticeSummary>> = withContext(Dispatchers.IO) {
    try {
        val url =
            "https://wx.dlmu.edu.cn/weapp/api/v1/notice/titles?deptId=&keys=&type=1019&start=$start&size=$size"
        val request = buildDlmuGetRequest(url, authorization)
        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful && bodyStr.isBlank()) {
                return@withContext Result.failure(Exception("请求失败 (${response.code})"))
            }
            val jsonObject = gson.fromJson(bodyStr, JsonObject::class.java)
                ?: return@withContext Result.failure(Exception("响应解析失败"))
            val code = jsonObject.optInt("code", -1)
            if (code != 200) {
                val msg = jsonObject.optString("msg", "请求失败 ($code)")
                return@withContext Result.failure(Exception(msg))
            }
            val dataElem = jsonObject.get("data")
            val list = if (dataElem != null && dataElem.isJsonArray) {
                dataElem.asJsonArray.mapNotNull { itemElem ->
                    if (!itemElem.isJsonObject) return@mapNotNull null
                    val obj = itemElem.asJsonObject
                    NoticeSummary(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        publishDeptName = obj.optString("publishDeptName"),
                        publishDeptId = obj.optString("publishDeptId"),
                        publishDate = obj.optString("publishDate"),
                        url = obj.optString("url")
                    )
                }
            } else {
                emptyList()
            }
            Result.success(list)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun extractWebNewsId(notice: NoticeSummary): String {
    val fromUrl = runCatching {
        notice.url.toUri().getQueryParameter("wbnewsid")?.takeIf { it.isNotBlank() }
    }.getOrNull()
    return fromUrl ?: notice.id
}

suspend fun fetchNoticeDetail(
    authorization: String,
    newsId: String
): Result<NoticeDetail> = withContext(Dispatchers.IO) {
    try {
        val url = "https://wx.dlmu.edu.cn/weapp/api/v1/notice/$newsId"
        val request = buildDlmuGetRequest(url, authorization)
        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful && bodyStr.isBlank()) {
                return@withContext Result.failure(Exception("请求失败 (${response.code})"))
            }
            val jsonObject = gson.fromJson(bodyStr, JsonObject::class.java)
                ?: return@withContext Result.failure(Exception("响应解析失败"))
            val code = jsonObject.optInt("code", -1)
            if (code != 200) {
                val msg = jsonObject.optString("msg", "请求失败 ($code)")
                return@withContext Result.failure(Exception(msg))
            }
            val dataObj = jsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@withContext Result.failure(Exception("公告详情为空"))
            val filesElem = dataObj.get("files")
            val files = if (filesElem != null && filesElem.isJsonArray) {
                filesElem.asJsonArray.mapNotNull { fileElem ->
                    if (!fileElem.isJsonObject) return@mapNotNull null
                    val fileObj = fileElem.asJsonObject
                    val name = fileObj.optString("name")
                    val fileUrl = fileObj.optString("url")
                    if (name.isBlank() && fileUrl.isBlank()) null
                    else NoticeFile(name = name.ifBlank { fileUrl }, url = fileUrl)
                }
            } else {
                emptyList()
            }
            Result.success(
                NoticeDetail(
                    id = dataObj.optString("id"),
                    title = dataObj.optString("title"),
                    publishUserName = dataObj.optString("publishUserName"),
                    publishDeptName = dataObj.optString("publishDeptName"),
                    publishDate = dataObj.optString("publishDate"),
                    click = dataObj.optInt("click", 0),
                    content = dataObj.optString("content"),
                    redirect = dataObj.optString("redirect"),
                    type = dataObj.optString("type"),
                    files = files
                )
            )
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun resolveNoticeUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("/") -> "https://info.dlmu.edu.cn$trimmed"
        else -> "https://info.dlmu.edu.cn/$trimmed"
    }
}

fun openExternalUrl(context: Context, rawUrl: String) {
    if (rawUrl.isBlank()) return
    val resolved = resolveNoticeUrl(rawUrl)
    try {
        val intent = Intent(Intent.ACTION_VIEW, resolved.toUri())
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开链接: $resolved", Toast.LENGTH_SHORT).show()
    }
}

fun Color.toCssHex(): String {
    val argb = toArgb()
    val rgb = argb and 0xFFFFFF
    return String.format("#%06X", rgb)
}

fun buildNoticeHtmlDocument(
    detail: NoticeDetail,
    textColor: Color,
    mutedColor: Color,
    linkColor: Color,
    dividerColor: Color,
    isDarkTheme: Boolean
): String {
    val titleEscaped = TextUtils.htmlEncode(detail.title)
    val metaParts = buildList {
        if (detail.publishDeptName.isNotBlank()) add(TextUtils.htmlEncode(detail.publishDeptName))
        if (detail.publishUserName.isNotBlank()) add("发布人: ${TextUtils.htmlEncode(detail.publishUserName)}")
        if (detail.publishDate.isNotBlank()) add(TextUtils.htmlEncode(detail.publishDate))
        if (detail.click > 0) add("阅读 ${detail.click}")
    }
    val metaHtml = metaParts.joinToString(" &nbsp;·&nbsp; ")

    val textHex = textColor.toCssHex()
    val mutedHex = mutedColor.toCssHex()
    val linkHex = linkColor.toCssHex()
    val dividerHex = dividerColor.toCssHex()

    val darkOverrideCss = if (isDarkTheme) {
        """
        .notice-content, .notice-content * {
            color: $textHex !important;
            background-color: transparent !important;
        }
        .notice-content a {
            color: $linkHex !important;
        }
        """.trimIndent()
    } else {
        ""
    }

    val bodyContent = if (detail.content.isNotBlank()) {
        detail.content
    } else if (detail.redirect.isNotBlank()) {
        "<p>该公告已跳转至外部链接，请点击下方或右上角在浏览器中打开。</p>"
    } else {
        "<p>暂无正文内容</p>"
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0" />
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background-color: transparent;
                    color: $textHex;
                    font-family: sans-serif !important;
                    font-size: 16px;
                    line-height: 1.7;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }
                * {
                    font-family: sans-serif !important;
                    box-sizing: border-box;
                    max-width: 100% !important;
                }
                .container {
                    padding: 12px 16px 24px 16px;
                }
                .title {
                    font-size: 20px;
                    font-weight: 700;
                    line-height: 1.4;
                    margin: 0 0 10px 0;
                    color: $textHex;
                }
                .meta {
                    font-size: 13px;
                    line-height: 1.5;
                    color: $mutedHex;
                    margin-bottom: 14px;
                }
                .divider {
                    height: 1px;
                    background-color: $dividerHex;
                    margin-bottom: 16px;
                }
                .notice-content p {
                    margin: 0.5em 0;
                }
                .notice-content img {
                    height: auto !important;
                    display: block;
                    margin: 8px auto;
                }
                .notice-content table {
                    width: 100% !important;
                    border-collapse: collapse;
                    display: block;
                    overflow-x: auto;
                    margin: 8px 0;
                }
                .notice-content td, .notice-content th {
                    border: 1px solid $dividerHex;
                    padding: 6px 8px;
                }
                .notice-content a {
                    color: $linkHex;
                    word-break: break-all;
                }
                $darkOverrideCss
            </style>
        </head>
        <body>
            <div class="container">
                <h1 class="title">$titleEscaped</h1>
                <div class="meta">$metaHtml</div>
                <div class="divider"></div>
                <div class="notice-content">$bodyContent</div>
            </div>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun AnimatedExpandDialog(
    showDialog: Boolean,
    buttonRect: Rect,
    rootSize: IntSize,
    onDismissRequest: () -> Unit,
    titleText: String,
    confirmButton: @Composable (() -> Unit) = {},
    dismissButton: @Composable (() -> Unit) = {},
    text: @Composable (() -> Unit)
) {
    BackHandler(enabled = showDialog) {
        onDismissRequest()
    }

    val animationSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
    val intOffsetAnimationSpec =
        tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

    AnimatedVisibility(
        visible = showDialog, enter = fadeIn(animationSpec), exit = fadeOut(animationSpec)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismissRequest() }
                })
    }

    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = showDialog, enter = fadeIn(animationSpec) + scaleIn(
                initialScale = 0.1f, animationSpec = animationSpec
            ) + slideIn(
                animationSpec = intOffsetAnimationSpec, initialOffset = {
                    IntOffset(
                        x = (buttonRect.center.x - rootSize.width / 2f).toInt(),
                        y = (buttonRect.center.y - rootSize.height / 2f).toInt()
                    )
                }), exit = fadeOut(animationSpec) + scaleOut(
                targetScale = 0.1f, animationSpec = animationSpec
            ) + slideOut(
                animationSpec = intOffsetAnimationSpec, targetOffset = {
                    IntOffset(
                        x = (buttonRect.center.x - rootSize.width / 2f).toInt(),
                        y = (buttonRect.center.y - rootSize.height / 2f).toInt()
                    )
                })
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .padding(all = 24.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    text()
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
