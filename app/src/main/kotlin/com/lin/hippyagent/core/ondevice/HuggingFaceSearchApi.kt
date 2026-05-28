package com.lin.hippyagent.core.ondevice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

@Serializable
data class HuggingFaceSibling(
    val rfilename: String = ""
)

@Serializable
data class HuggingFaceModel(
    val id: String = "",
    val modelId: String = "",
    val author: String? = null,
    val pipeline_tag: String? = null,
    val tags: List<String> = emptyList(),
    val downloads: Long = 0,
    val library_name: String? = null,
    val siblings: List<HuggingFaceSibling> = emptyList(),
) {
    val displayName: String get() = modelId.ifEmpty { id }
}

object HuggingFaceSearchApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = com.lin.hippyagent.core.model.sharedOkHttpClient

    suspend fun search(
        query: String = "",
        pipeline: String = "text-generation",
        limit: Int = 30,
    ): Result<List<HuggingFaceModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val host = HuggingFaceMirror.resolveUrl("https://huggingface.co")
                .removeSuffix("/")
            val url = buildString {
                append("$host/api/models?")
                append("sort=downloads&direction=-1&limit=$limit")
                if (query.isNotBlank()) append("&search=${java.net.URLEncoder.encode(query, "UTF-8")}")
                append("&filter=$pipeline")
            }
            Timber.d("HuggingFaceSearchApi: $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("API request failed: ${response.code}")
                }
                val body = response.body?.string() ?: throw RuntimeException("Empty response")
                json.decodeFromString<List<HuggingFaceModel>>(body)
            }
        }
    }
}
