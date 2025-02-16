package com.example.downm3u8

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.provider.MediaStore
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
import java.io.FileInputStream
import java.io.OutputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var consoleText: TextView
    private lateinit var clearButton: Button
    private lateinit var clearCatchButton: Button
    private lateinit var parseButton: Button
    private lateinit var exitButton: Button
    private lateinit var mergeButton: Button // 新增合并按钮
    private lateinit var outNameInput: EditText // 原来是ffmpeg命令后面的参数，后改为输出文件的文件名

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化控件
        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        consoleText = findViewById(R.id.consoleText)
        clearButton = findViewById(R.id.clearButton)
        clearCatchButton = findViewById(R.id.clearCatchButton)
        parseButton = findViewById(R.id.parseButton)
        exitButton = findViewById(R.id.exitButton)
        mergeButton = findViewById(R.id.mergeButton) // 初始化合并按钮
        outNameInput = findViewById(R.id.outNameInput) // 初始化 Ffmpeg 命令输入框


        // 设置下载TS按钮点击事件,这里的判断有误，应该是判断目录下是否有m3u8文件
        downloadButton.setOnClickListener {
            val m3u8File = File(getExternalFilesDir(null), "index.m3u8")
            if (m3u8File.exists()) {
                downloadAllTs()
            } else {
                consoleText.text = getString(R.string.con_m3u8NotFound)
            }
        }

        clearButton.setOnClickListener {
            consoleText.text = "运行日志"
        }

        clearCatchButton.setOnClickListener {
            val tempDir = getExternalFilesDir(null)
            if (tempDir != null) {
                clearCacheFiles(tempDir)
            }
        }

        // 设置解析按钮点击事件
        parseButton.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                // 设置 outNameInput 的初始内容为out
                outNameInput.setText(getString(R.string.DefaultOutName)) //"-y -f concat -safe 0 -i input_file -c copy out.mp4")

                parseM3u8Content(url)
            } else {
                Toast.makeText(this, "请输入有效的URL", Toast.LENGTH_SHORT).show()
            }
        }

        // 设置合并按钮点击事件
        mergeButton.setOnClickListener {
            // 从本地文件 index.m3u8 读取 TS 文件 URL 列表
            val indexFile = File(getExternalFilesDir(null), "index.m3u8")
            if (!indexFile.exists()) {
                consoleText.append("\n未找到 index.m3u8 文件，请先解析 M3U8 文件")
                return@setOnClickListener
            }

            val tsUrls = indexFile.readLines()
                .filter { !it.startsWith("#") && it.isNotBlank() }

            if (tsUrls.isNotEmpty()) {
                // 获取所有 TS 文件
                val tsFiles = tsUrls.map { File(getExternalFilesDir(null), it.substringAfterLast("/")) }
                val outName = outNameInput.text.toString() + ".mp4"
                // 调用 mergeTsFiles 函数
                mergeTsFiles(tsFiles, File(getExternalFilesDir(null), outName))
            } else {
                consoleText.append("\n未找到ts文件链接")
            }
        }

        // 设置退出按钮点击事件
        exitButton.setOnClickListener {
            finish() // 退出应用
        }
    }


    /**
     * 解析 M3U8 文件内容，并保存到本地文件 index.m3u8。
     *
     * @param url M3U8 文件的 URL。
     */
    private fun parseM3u8Content(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            consoleText.text = getString(R.string.con_StartParse)
            val m3u8Content = withContext(Dispatchers.IO) {
                downloadM3u8File(url)
            }

            if (m3u8Content != null) {
                // 检查是否需要第二层 M3U8
                val secondLayerUrl = checkForSecondLayerM3u8(m3u8Content, url)
                val (finalM3u8Content, finalBaseUrl) = if (secondLayerUrl != null) {
                    consoleText.append("\n检测到第二层 M3U8，开始下载...")
                    val secondLayerContent = withContext(Dispatchers.IO) {
                        downloadM3u8File(secondLayerUrl)
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
                updatedLine = line.replace(uri, key)
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
     * 下载所有 ts 文件，如果已经存在则跳过。
     */
    private fun downloadAllTs() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 从本地文件 index.m3u8 读取 TS 文件 URL 列表
                val indexFile = File(getExternalFilesDir(null), "index.m3u8")
                if (!indexFile.exists()) {
                    consoleText.append("\n未找到 index.m3u8 文件，请先解析 M3U8 文件")
                    return@launch
                }

                val tsUrls = indexFile.readLines()
                    .filter { !it.startsWith("#") && it.isNotBlank() }

                if (tsUrls.isNotEmpty()) {
                    consoleText.append("\n视频片段一共: ${tsUrls.size}")
                    consoleText.append("\n开始下载ts文件...")
                    val tsFiles = mutableListOf<File>()
                    for ((index, tsUrl) in tsUrls.withIndex()) {
                        // 生成目标文件的路径
                        val fileName = tsUrl.substringAfterLast("/")
                        val targetFile = File(getExternalFilesDir(null), fileName)

                        if (targetFile.exists()) {
                            // 文件已经存在，跳过下载
                            consoleText.append("\n文件 $fileName 已存在，跳过下载")
                            tsFiles.add(targetFile)
                        } else {
                            // 文件不存在，进行下载
                            val tsFile = downloadSingleTs(tsUrl)
                            if (tsFile != null) {
                                tsFiles.add(tsFile)
                            }
                        }
                        consoleText.text = getString(R.string.downAllTs_Progress, index + 1, tsUrls.size)
                        Log.d("Ts-down", "进度: ${index + 1}/${tsUrls.size} $tsUrl")
                    }
                    consoleText.text = ""
                    consoleText.append("\n${tsUrls.size} 个 ts 文件下载完成")

                } else {
                    consoleText.append("\n提供的 m3u8 里未找到 ts 文件链接")
                }
            } catch (e: Exception) {
                consoleText.text = getString(R.string.error_message, e.message)
                Log.e("MainActivity", "Error: ${e.message}", e)
            }
        }
    }

    /**
     * 下载m3u8文件内容。
     *
     * @param url 文件的 URL。
     * @return 文件内容，如果下载失败则返回 null。
     */
    private suspend fun downloadM3u8File(url: String): String? {
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

    private val client = OkHttpClient()
    /**
     * 下载 TS 文件。
     *
     * @param url TS 文件的 URL。
     * @return 下载后的文件对象，如果下载失败则返回 null。
     */
    private suspend fun downloadSingleTs(url: String): File? {
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

        // 使用 ffmpegInput 中的命令
        val baseCommand = "-y -f concat -safe 0 -i ${tsFileList.name} -c copy ${outputFile.name}"
        val command = baseCommand
            .replace(tsFileList.name, tsFileList.absolutePath)
            .replace(outputFile.name, outputFile.absolutePath)

        consoleText.append("\nffmpeg $command")

        FFmpeg.executeAsync(command) { _, returnCode ->
            if (returnCode == Config.RETURN_CODE_SUCCESS) {
                Log.d("Ts-Ffmpeg", "合并成功: ${outputFile.absolutePath}")
                consoleText.append("\n合并成功: ${outputFile.absolutePath}")

                moveVideoToCameraAlbum(outputFile)
            } else {
                Log.e("Ts-Ffmpeg", "合并失败，返回码: $returnCode")
                consoleText.append("\n合并失败，返回码: $returnCode")
            }
        }
    }
    private fun moveVideoToCameraAlbum(sourceFile: File) {
        val contentResolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri
            ?.let {
                try {
                    val inputStream = FileInputStream(sourceFile)
                    val outputStream: OutputStream = contentResolver.openOutputStream(it)!!
                    inputStream
                        .copyTo(outputStream)
                    inputStream
                        .close()
                    outputStream
                        .close()

                    // 移动成功后删除源文件
                    sourceFile
                        .delete()

                    consoleText
                        .append("\n视频已移动到相机相册: ${sourceFile.name}")
                } catch (e: Exception) {
                    e
                        .printStackTrace()
                    consoleText
                        .append("\n移动视频到相机相册失败: ${e.message}")
                }
            }
    }

    /**
     * delete ts files , ts_files.txt, index.m3u8。
     */
    private fun clearCacheFiles(tempDir: File) {

        val tsFileList = File(tempDir, "ts_files.txt")
        val indexFile = File(tempDir, "index.m3u8")
        val tsFiles = tsFileList.readLines()
            .mapNotNull { line ->
                // 匹配双引号之间的内容
                val regex = "\'([^\"]*)\'".toRegex()
                val matchResult = regex.find(line)
                matchResult?.groupValues?.getOrNull(1)?.let { File(it) }
            }

        // 遍历所有已合并的 TS 文件，将它们从存储设备中删除
        consoleText.append("\n" + "$(tsFiles.count()) ts deleted.")

        tsFiles.forEach { it.delete() }
        // 删除之前创建的临时文件，避免占用额外的存储空间
        tsFileList.delete()
        //del index.m3u8 也可以保留，但是每个电影必须有自己的子目录
        indexFile.delete()
        //move video to album
    }
}