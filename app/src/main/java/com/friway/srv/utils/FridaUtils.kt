package com.friway.srv.utils

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object FridaUtils {
    private const val FRIDA_GITHUB_API = "https://api.github.com/repos/frida/frida/releases"
    const val FRIDA_BINARY_PATH = "/data/local/tmp/fr-srv"
    private const val FRIDA_VERSION_FILE = "/data/local/tmp/fr-v.txt"

    data class FridaRelease(
        val version: String,
        val releaseDate: String,
        val assets: List<FridaAsset>
    )

    data class FridaAsset(
        val name: String,
        val downloadUrl: String,
        val architecture: String,
        val size: Long
    )

    data class GithubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("published_at") val publishedAt: String,
        @SerializedName("assets") val assets: List<GithubAsset>
    )

    data class GithubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("size") val size: Long
    )

    suspend fun getAvailableFridaReleases(): List<FridaRelease> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(FRIDA_GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val releases = mutableListOf<FridaRelease>()
                val gson = Gson()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }

                    JsonReader(response.body?.charStream() ?: return@withContext emptyList()).use { reader ->
                        reader.beginArray()

                        while (reader.hasNext()) {
                            val githubRelease = gson.fromJson<GithubRelease>(reader, GithubRelease::class.java)
                            val fridaAssets = mutableListOf<FridaAsset>()

                            for (asset in githubRelease.assets) {
                                val name = asset.name
                                if (name.startsWith("frida-server-") && (name.endsWith(".xz") || name.endsWith(".zip"))) {
                                    val archPattern = "android-(arm|arm64|x86|x86_64)".toRegex()
                                    val matchResult = archPattern.find(name)
                                    val architecture = matchResult?.groupValues?.get(1) ?: "unknown"

                                    fridaAssets.add(
                                        FridaAsset(
                                            name = name,
                                            downloadUrl = asset.browserDownloadUrl,
                                            architecture = architecture,
                                            size = asset.size
                                        )
                                    )
                                }
                            }

                            if (fridaAssets.isNotEmpty()) {
                                releases.add(
                                    FridaRelease(
                                        version = githubRelease.tagName,
                                        releaseDate = githubRelease.publishedAt.split("T")[0],
                                        assets = fridaAssets
                                    )
                                )
                            }
                        }
                        reader.endArray()
                    }
                }
                return@withContext releases
            } catch (e: Exception) {
                return@withContext emptyList()
            }
        }
    }

    suspend fun getFridaServerUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                val release = releases.find { it.version == version }

                if (release != null) {
                    val asset = release.assets.find { it.architecture == architecture }
                    if (asset != null) {
                        return@withContext asset.downloadUrl
                    }

                    val fallbackAsset = release.assets.find { it.name.contains("-android-$architecture") }
                    if (fallbackAsset != null) {
                        return@withContext fallbackAsset.downloadUrl
                    }
                }

                return@withContext buildCustomVersionUrl(version, architecture)
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    private suspend fun buildCustomVersionUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fileName = "frida-server-$version-android-$architecture.xz"
                val url = "https://github.com/frida/frida/releases/download/$version/$fileName"

                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 302) {
                        return@withContext url
                    } else {
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    fun isValidVersionFormat(version: String): Boolean {
        val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9]+)?$")
        return versionPattern.matches(version)
    }

    suspend fun getLatestFridaServerUrl(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                if (releases.isEmpty()) {
                    return@withContext null
                }

                val latestRelease = releases.first()
                val arch = getDeviceArchitecture()
                val asset = latestRelease.assets.find { it.architecture == arch }

                if (asset != null) {
                    return@withContext asset.downloadUrl
                }

                val fallbackAsset = latestRelease.assets.find { it.name.contains("-android-$arch") }
                if (fallbackAsset != null) {
                    return@withContext fallbackAsset.downloadUrl
                }

                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    suspend fun downloadFridaServerFromUrl(context: Context, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val downloadRequest = Request.Builder().url(url).build()
                val fridaFile = File(context.filesDir, "frida-server")

                client.newCall(downloadRequest).execute().use { downloadResponse ->
                    if (!downloadResponse.isSuccessful) {
                        return@withContext null
                    }

                    downloadResponse.body?.let { responseBody ->
                        if (url.endsWith(".xz")) {
                            val compressedFile = File(context.filesDir, "frida-server.xz")

                            FileOutputStream(compressedFile).use { output ->
                                responseBody.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }

                            try {
                                XZInputStream(FileInputStream(compressedFile)).use { xzInputStream ->
                                    FileOutputStream(fridaFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (xzInputStream.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                                compressedFile.delete()
                            } catch (e: Exception) {
                                compressedFile.delete()
                                if (fridaFile.exists()) {
                                    fridaFile.delete()
                                }
                                throw IOException("Failed to decompress Frida server XZ file", e)
                            }
                        } else if (url.endsWith(".zip")) {
                            ZipInputStream(responseBody.byteStream()).use { zipStream ->
                                var entry = zipStream.nextEntry
                                while (entry != null) {
                                    if (entry.name.contains("frida-server")) {
                                        FileOutputStream(fridaFile).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                            }
                                        }
                                        break
                                    }
                                    zipStream.closeEntry()
                                    entry = zipStream.nextEntry
                                }
                            }
                        } else {
                            FileOutputStream(fridaFile).use { output ->
                                responseBody.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                    }
                }

                fridaFile.setExecutable(true)
                return@withContext fridaFile
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    fun getDeviceArchitecture(): String {
        return when (Build.SUPPORTED_ABIS[0]) {
            "armeabi-v7a" -> "arm"
            "arm64-v8a" -> "arm64"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> "arm"
        }
    }

    suspend fun installFridaServer(fridaFile: File, version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                executeSuCommand("cp ${fridaFile.absolutePath} $FRIDA_BINARY_PATH")
                executeSuCommand("chmod 755 $FRIDA_BINARY_PATH")
                saveInstalledVersion(version)

                val isInstalled = isFridaServerInstalled()
                return@withContext isInstalled
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun saveInstalledVersion(version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                executeSuCommand("echo '$version' > $FRIDA_VERSION_FILE")
                return@withContext true
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun getInstalledFridaVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val checkResult = executeSuCommand("ls -la $FRIDA_VERSION_FILE")
                if (!checkResult.contains(FRIDA_VERSION_FILE) || checkResult.contains("No such file")) {
                    return@withContext null
                }

                val versionResult = executeSuCommand("cat $FRIDA_VERSION_FILE")
                if (versionResult.isNotEmpty()) {
                    return@withContext versionResult.trim()
                }

                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    suspend fun startFridaServer(): Boolean {
        return startFridaServerWithFlags("")
    }

    suspend fun startFridaServerWithFlags(flags: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isFridaServerRunning()) {
                    return@withContext true
                }

                val command = if (flags.isBlank()) {
                    "nohup $FRIDA_BINARY_PATH > /dev/null 2>&1 &"
                } else {
                    "nohup $FRIDA_BINARY_PATH $flags > /dev/null 2>&1 &"
                }

                executeSuCommand(command)
                Thread.sleep(1500)

                val isRunning = isFridaServerRunning()
                return@withContext isRunning
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun stopFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                executeSuCommand("kill -9 \$(ps -A | grep fr-srv | awk '{ print \$2 }')")
                Thread.sleep(500)

                var isRunning = isFridaServerRunning()
                if (!isRunning) {
                    return@withContext true
                }

                executeSuCommand("kill -9 \$(pidof fr-srv)")
                Thread.sleep(300)

                isRunning = isFridaServerRunning()
                if (!isRunning) {
                    return@withContext true
                }

                executeSuCommand("kill -9 \$(ps | grep fr-srv | awk '{ print \$2 }')")
                Thread.sleep(300)

                isRunning = isFridaServerRunning()
                if (!isRunning) {
                    return@withContext true
                }

                executeSuCommand("pkill -9 -f fr-srv")
                Thread.sleep(500)

                isRunning = isFridaServerRunning()
                return@withContext !isRunning
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    private var suProcess: Process? = null
    private var suOutputStream: java.io.OutputStream? = null

    fun getSuProcess(): Pair<Process, java.io.OutputStream>? {
        if (suProcess == null || suOutputStream == null) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = process.outputStream
                suProcess = process
                suOutputStream = outputStream
            } catch (e: Exception) {
                return null
            }
        }
        return Pair(suProcess!!, suOutputStream!!)
    }

    fun closeSuProcess() {
        try {
            suOutputStream?.let {
                it.write("exit\n".toByteArray())
                it.flush()
                it.close()
            }
            suProcess?.destroy()
            suProcess = null
            suOutputStream = null
        } catch (e: Exception) { }
    }

    fun executeSuCommand(command: String): String {
        if (!isRootAvailable()) {
            return ""
        }

        val suProcessPair = getSuProcess() ?: return ""
        val (process, outputStream) = suProcessPair

        try {
            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
            Thread.sleep(500)

            val inputStream = process.inputStream
            val available = inputStream.available()
            val buffer = ByteArray(if (available > 0) available else 1024)
            val output = StringBuilder()

            while (inputStream.available() > 0 && inputStream.read(buffer) != -1) {
                output.append(String(buffer))
            }

            return output.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    suspend fun isFridaServerInstalled(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val result = executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                val isInstalled = result.contains(FRIDA_BINARY_PATH) && !result.contains("No such file")
                return@withContext isInstalled
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun uninstallFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isFridaServerRunning()) {
                    stopFridaServer()
                }

                executeSuCommand("rm -f $FRIDA_BINARY_PATH")
                executeSuCommand("rm -f $FRIDA_VERSION_FILE")

                val result = executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                val isInstalled = result.contains(FRIDA_BINARY_PATH) && !result.contains("No such file")

                return@withContext !isInstalled
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun isFridaServerRunning(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var result = executeSuCommand("ps -A | grep fr-srv")
                if (result.contains("fr-srv")) {
                    return@withContext true
                }

                result = executeSuCommand("ps | grep fr-srv")
                if (result.contains("fr-srv")) {
                    return@withContext true
                }

                result = executeSuCommand("pidof fr-srv")
                if (result.trim().isNotEmpty()) {
                    return@withContext true
                }

                return@withContext false
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val exitValue = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            exitValue == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun canUseNonRootMode(context: Context): Boolean {
        return try {
            val testFile = File(context.filesDir, "test.sh")
            FileOutputStream(testFile).use { output ->
                output.write("#!/system/bin/sh\necho 'test'\n".toByteArray())
            }
            testFile.setExecutable(true)
            val process = Runtime.getRuntime().exec(testFile.absolutePath)
            val exitValue = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            testFile.delete()
            exitValue == 0 && output.contains("test")
        } catch (e: Exception) {
            false
        }
    }
}
