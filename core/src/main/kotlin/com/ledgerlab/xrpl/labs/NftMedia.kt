package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** How an NFT's URI describes it. All are valid on the XRPL; they differ in what wallets and marketplaces read. */
enum class MetadataStandard(val label: String, val blurb: String) {
    XLS24(
        "XLS-24",
        "The XRPL community metadata standard: JSON with name, description, image, separate video/audio/animation fields, a collection and attributes.",
    ),
    OPENSEA(
        "OpenSea style",
        "The ERC-721 style JSON most cross-chain marketplaces understand: name, description, image, animation_url and attributes.",
    ),
    MEDIA_ONLY(
        "Media link only",
        "The URI points straight at the file. Smallest and simplest, but there's no name, description or traits.",
    ),
}

data class NftAttribute(val trait: String, val value: String)

/** Builds NFT metadata JSON for the chosen standard. */
object NftMetadata {
    /**
     * [mediaUri] is where the file lives (normally `ipfs://<cid>`), [mimeType] decides which field it goes in.
     * Returns null for [MetadataStandard.MEDIA_ONLY], which has no JSON.
     */
    fun build(
        standard: MetadataStandard,
        name: String,
        description: String,
        mediaUri: String,
        mimeType: String,
        collection: String = "",
        attributes: List<NftAttribute> = emptyList(),
    ): JsonObject? {
        val kind = mimeType.substringBefore('/')
        return when (standard) {
            MetadataStandard.MEDIA_ONLY -> null
            MetadataStandard.XLS24 -> buildJsonObject {
                put("nftType", "art.v0")
                put("name", name)
                put("description", description)
                when (kind) {
                    "image" -> put("image", mediaUri)
                    "video" -> put("video", mediaUri)
                    "audio" -> put("audio", mediaUri)
                    else -> put("file", mediaUri)
                }
                if (mimeType == "image/gif") put("animation", mediaUri)
                if (collection.isNotBlank()) {
                    putJsonObject("collection") {
                        put("name", collection)
                        put("family", collection)
                    }
                }
                put("attributes", attributesJson(attributes))
            }
            MetadataStandard.OPENSEA -> buildJsonObject {
                put("name", name)
                put("description", description)
                // Marketplaces need an image; video and audio also go in animation_url, which they play.
                put("image", mediaUri)
                if (kind == "video" || kind == "audio") put("animation_url", mediaUri)
                put("attributes", attributesJson(attributes))
            }
        }
    }

    /** Parses "trait=value, trait=value" from the mint form. */
    fun parseAttributes(text: String): List<NftAttribute> = text.split(',', ';', '\n')
        .mapNotNull { part ->
            val (k, v) = part.split('=', ':', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            if (k.isEmpty() || v.isEmpty()) null else NftAttribute(k, v)
        }

    private fun attributesJson(attributes: List<NftAttribute>) = buildJsonArray {
        attributes.forEach { a ->
            add(buildJsonObject {
                put("trait_type", a.trait)
                put("value", a.value)
            })
        }
    }
}

/** Turns ipfs:// links into something an HTTP client can fetch, and finds an NFT's picture. */
object IpfsGateway {
    val gateways = listOf("https://ipfs.io/ipfs/", "https://dweb.link/ipfs/")

    /** `ipfs://cid/path` (or a bare CID) to a gateway URL; http(s) links pass through. Null for anything else. */
    fun toHttp(uri: String, gateway: String = gateways.first()): String? {
        val u = uri.trim()
        return when {
            u.startsWith("ipfs://ipfs/") -> gateway + u.removePrefix("ipfs://ipfs/")
            u.startsWith("ipfs://") -> gateway + u.removePrefix("ipfs://")
            u.startsWith("https://") || u.startsWith("http://") -> u
            looksLikeCid(u) -> gateway + u
            else -> null
        }
    }

    fun looksLikeCid(text: String) = (text.startsWith("Qm") && text.length == 46) || (text.startsWith("bafy") && text.length > 50)

    /**
     * Resolves an NFT URI to a fetchable picture URL: the URI itself when it's media, or the `image`
     * field of its metadata JSON. Blocking; null when there's no picture.
     */
    fun imageUrl(uri: String, http: OkHttpClient = XrplRpc.defaultHttp): String? {
        val url = toHttp(uri) ?: return null
        val request = Request.Builder().url(url).header("Range", "bytes=0-65535").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            val looksJson = type.contains("json") || type.startsWith("text/plain") || uri.endsWith(".json")
            if (!looksJson) return url
            val text = response.body?.string().orEmpty()
            val json = runCatching { StreamParser.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val image = json.string("image") ?: json.string("image_url") ?: return null
            return toHttp(image)
        }
    }
}

/** Pins files and JSON to IPFS through Pinata, using an API JWT the user pastes in. Blocking. */
class PinataClient(private val jwt: String, private val http: OkHttpClient = uploadHttp) {
    companion object {
        /** Uploads of a few megabytes over mobile data need more than the RPC client's timeouts. */
        val uploadHttp: OkHttpClient by lazy {
            XrplRpc.defaultHttp.newBuilder()
                .writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
                .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
                .build()
        }
    }

    /** Uploads the file and returns its CID. */
    fun pinFile(fileName: String, mimeType: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .addFormDataPart("pinataMetadata", buildJsonObject { put("name", fileName) }.toString())
            .build()
        return send("pinning/pinFileToIPFS", body)
    }

    /** Uploads a JSON document and returns its CID. */
    fun pinJson(name: String, content: JsonObject): String {
        val body = buildJsonObject {
            put("pinataContent", content)
            putJsonObject("pinataMetadata") { put("name", name) }
        }.toString().toRequestBody("application/json".toMediaType())
        return send("pinning/pinJSONToIPFS", body)
    }

    private fun send(path: String, body: okhttp3.RequestBody): String {
        val request = Request.Builder()
            .url("https://api.pinata.cloud/$path")
            .header("Authorization", "Bearer ${jwt.trim()}")
            .post(body)
            .build()
        val text = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 || response.code == 403 -> throw IOException("Pinata rejected the API key. Check the JWT and its pinning permissions.")
                !response.isSuccessful -> throw IOException("Pinata answered HTTP ${response.code}: ${text.take(160)}")
            }
            text
        }
        val json = runCatching { StreamParser.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return json?.string("IpfsHash") ?: throw IOException("Pinata didn't return a CID.")
    }
}
