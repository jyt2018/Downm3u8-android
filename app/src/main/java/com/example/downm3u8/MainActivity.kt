package com.example.downm3u8

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var consoleText: TextView
    private lateinit var clearButton: Button
    private lateinit var pasteButton: Button
    private lateinit var parseButton: Button
    private lateinit var exitButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化控件
        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        consoleText = findViewById(R.id.consoleText)
        clearButton = findViewById(R.id.clearButton)
        pasteButton = findViewById(R.id.pasteButton)
        parseButton = findViewById(R.id.parseButton)
        exitButton = findViewById(R.id.exitButton)

        // 设置按钮点击事件
        downloadButton.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                downloadAndMergeTs(url)
            } else {
                consoleText.text = "请输入有效的URL"
            }
        }

        clearButton.setOnClickListener {
            consoleText.text = "控制台输出"
        }

        pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        // 设置解析按钮点击事件
        parseButton.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                parseM3u8Content(url)
            } else {
                Toast.makeText(this, "请输入有效的URL", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置退出按钮点击事件
        exitButton.setOnClickListener {
            finish() // 退出应用
        }
    }

    /**
     * 从剪贴板粘贴内容到 EditText。
     */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clipData: ClipData? = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val textToPaste = clipData.getItemAt(0).text.toString()
                urlInput.setText("")
                urlInput.setText(textToPaste)
                Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
        }
    }
    /**
     * 解析 M3U8 文件内容，并保存到本地文件 index.m3u8。
     *
     * @param url M3U8 文件的 URL。
     */
    private fun parseM3u8Content(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            consoleText.text = "开始解析url..."
            val m3u8Content = withContext(Dispatchers.IO) {
                downloadFile(url)
            }

            if (m3u8Content != null) {
                // 检查是否需要第二层 M3U8
                val secondLayerUrl = checkForSecondLayerM3u8(m3u8Content, url)
                val (finalM3u8Content, finalBaseUrl) = if (secondLayerUrl != null) {
                    consoleText.append("\n检测到第二层 M3U8，开始下载...")
                    val secondLayerContent = withContext(Dispatchers.IO) {
                        downloadFile(secondLayerUrl)
                    }
                    if (secondLayerContent != null) {
                        consoleText.append("\n第二层 M3U8 下载成功")
                        Pair(secondLayerContent, secondLayerUrl.substringBeforeLast("/") + "/")
                    } else {
                        consoleText.append("\n第二层 M3U8 下载失败")
                        Pair(m3u8Content, url.substringBeforeLast("/") + "/")
                    }
                } else {
                    Pair(m3u8Content, url.substringBeforeLast("/") + "/")
                }

                // 解析 M3U8 内容
                val (key, tsCount, updatedM3u8Content) = parseAndUpdateM3u8(finalM3u8Content, finalBaseUrl)

                // 保存更新后的 M3U8 内容到本地文件
                val indexFile = File(getExternalFilesDir(null), "index.m3u8")
                indexFile.writeText(updatedM3u8Content)

                // 显示解析结果
                consoleText.append("\n解析结果:")
                consoleText.append("\nKEY=\"$key\"")
                consoleText.append("\nTS片段: $tsCount 个")
                consoleText.append("\nM3U8 文件已保存到: ${indexFile.absolutePath}")

                // 显示前 200 个字符
                val previewContent = if (finalM3u8Content.length > 200) {
                    finalM3u8Content.substring(0, 200) + "..."
                } else {
                    finalM3u8Content
                }
                consoleText.append("\nM3U8 内容 (前200个字符):\n$previewContent")
            } else {
                consoleText.append("\n解析失败，请检查URL是否正确")
            }
        }
    }


    /**
     * 检查是否需要第二层 M3U8。
     *
     * @param content M3U8 文件的文本内容。
     * @param baseUrl M3U8 文件的 URL。
     * @return 第二层 M3U8 的 URL，如果不需要则返回 null。
     */
    private fun checkForSecondLayerM3u8(content: String, baseUrl: String): String? {
        val lines = content.lines()
        for (i in lines.indices) {
            if (lines[i].contains("#EXT-X-STREAM-INF")) {
                // 找到下一行作为第二层 M3U8 的 URL
                val secondLayerPath = lines[i + 1].trim()
                return when {
                    secondLayerPath.startsWith("http") -> secondLayerPath // 完整 URL
                    secondLayerPath.startsWith("/") -> {
                        // 获取域名部分
                        val domain = baseUrl.substringBefore("/", "")
                        "$domain$secondLayerPath" // 拼接域名和绝对路径
                    }
                    else -> {
                        // 拼接相对路径
                        val basePath = baseUrl.substringBeforeLast("/")
                        "$basePath/$secondLayerPath"
                    }
                }
            }
        }
        return null
    }
    /**
     * 解析并更新 M3U8 内容。
     *
     * @param content M3U8 文件的文本内容。
     * @param baseUrl M3U8 文件的基础 URL（用于拼接相对路径）。
     * @return 包含 KEY、TS 片段数量和更新后的 M3U8 内容的三元组。
     */
    private fun parseAndUpdateM3u8(content: String, baseUrl: String): Triple<String?, Int, String> {
        val lines = content.lines()
        val updatedLines = mutableListOf<String>()
        var key: String? = null
        var tsCount = 0

        for (line in lines) {
            var updatedLine = line

            // 解析 KEY
            if (line.startsWith("#EXT-X-KEY")) {
                val uri = line.substringAfter("URI=").trim('"')
                key = when {
                    uri.startsWith("http") -> uri // 完整 URL
                    uri.startsWith("/") -> {
                        // 获取域名部分
                        val domain = baseUrl.substringBefore("/", "")
                        "$domain$uri" // 拼接域名和绝对路径
                    }
                    else -> "$baseUrl$uri" // 相对路径
                }
                updatedLine = line.replace(uri, key!!)
            }

            // 解析 TS 片段
            if (line.startsWith("#EXTINF")) {
                tsCount++
            } else if (!line.startsWith("#") && line.isNotBlank()) {
                updatedLine = when {
                    line.startsWith("http") -> line // 完整 URL
                    line.startsWith("/") -> {
                        // 获取域名部分
                        val domain = baseUrl.substringBefore("/", "")
                        "$domain$line" // 拼接域名和绝对路径
                    }
                    else -> "$baseUrl$line" // 相对路径
                }
            }

            updatedLines.add(updatedLine)
        }

        return Triple(key, tsCount, updatedLines.joinToString("\n"))
    }
    /**
     * 下载并合并 ts 文件。
     *
     * @param url M3U8 文件的 URL。
     */
    private fun downloadAndMergeTs(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                consoleText.text = "开始下载文件..."

                // 从本地文件 index.m3u8 读取 TS 文件 URL 列表
                val indexFile = File(getExternalFilesDir(null), "index.m3u8")
                if (!indexFile.exists()) {
                    consoleText.append("\n未找到 index.m3u8 文件，请先解析 M3U8 文件")
                    return@launch
                }

                val tsUrls = indexFile.readLines()
                    .filter { !it.startsWith("#") && it.isNotBlank() }

                if (tsUrls.isNotEmpty()) {
                    consoleText.append("\ntotal: {${tsUrls.size}}")
                    consoleText.append("\n开始下载ts文件...")
                    val tsFiles = mutableListOf<File>()
                    for ((index, tsUrl) in tsUrls.withIndex()) {

                        val tsFile = downloadTsFile(tsUrl)
                        if (tsFile != null) {
                            tsFiles.add(tsFile)
                        }
                        Log.d("Ts-down","进度: ${index + 1}/${tsUrls.size} $tsUrl")
                    }

                    consoleText.append("\n开始合并ts文件...")
                    val outputFile = File(getExternalFilesDir(null), "output.mp4")
                    mergeTsFiles(tsFiles, outputFile)
                    consoleText.append("\n合并完成，文件保存在: ${outputFile.absolutePath}")
                } else {
                    consoleText.append("\n未找到ts文件链接")
                }
            } catch (e: Exception) {
                consoleText.text = "发生错误: ${e.message}"
                Log.e("MainActivity", "Error: ${e.message}", e)
            }
        }
    }

    /**
     * 下载文件内容。
     *
     * @param url 文件的 URL。
     * @return 文件内容，如果下载失败则返回 null。
     */
    private suspend fun downloadFile(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                response.body?.string()
            } catch (e: Exception) {
                Log.e("DownloadFile", "Error: ${e.message}")
                null
            }
        }
    }

    /**
     * 下载 TS 文件。
     *
     * @param url TS 文件的 URL。
     * @return 下载后的文件对象，如果下载失败则返回 null。
     */
    private val client = OkHttpClient()
    private suspend fun downloadTsFile(url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val inputStream = response.body?.byteStream() ?: return@withContext null
                val file = File(getExternalFilesDir(null), url.substringAfterLast("/"))
                inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                file
            } catch (e: Exception) {
                Log.e("DownloadTsFile", "Error: ${e.message}")
                null
            }
        }
    }

    /**
     * 合并多个 TS 文件为一个指定的输出文件。
     *
     * @param tsFiles 待合并的 TS 文件列表。
     * @param outputFile 合并后的输出文件。
     */
    private fun mergeTsFiles(tsFiles: List<File>, outputFile: File) {
        val tsFileList = File(getExternalFilesDir(null), "ts_files.txt")
        tsFileList.writeText(tsFiles.joinToString("\n") { "file '${it.absolutePath}'" })
//        val command = "-allowed_extensions -ALL  -i ${getExternalFilesDir(null)}/index.m3u8 -acodec copy -vcodec copy -f mp4 ${outputFile.absolutePath}"
//        val command = "-f concat -i ${tsFileList.absolutePath}  -c copy ${outputFile.absolutePath}"
        val command = "-f concat -i ts_files.txt  -c copy out.mp4"
        consoleText.append("\nffmpeg ${command}")
//        val command = "-f concat -safe 0 -i ${tsFileList.absolutePath} -c copy ${outputFile.absolutePath}"
        FFmpeg.executeAsync(command) { executionId, returnCode ->
            if (returnCode == Config.RETURN_CODE_SUCCESS) {
                Log.d("Ts-FFmpeg", "合并成功: ${outputFile.absolutePath}")
//                tsFiles.forEach { it.delete() }
//                tsFileList.delete()
            } else {
                Log.e("Ts-FFmpeg", "合并失败，返回码: $returnCode")
            }
        }
    }
}