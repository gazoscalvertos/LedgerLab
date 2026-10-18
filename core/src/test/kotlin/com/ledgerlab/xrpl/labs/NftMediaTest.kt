package com.ledgerlab.xrpl.labs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NftMediaTest {
    private val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"

    @Test
    fun xls24PutsMediaInTheRightField() {
        val json = NftMetadata.build(
            MetadataStandard.XLS24, "Lab Rat #1", "First of many", "ipfs://$cid", "video/mp4",
            collection = "Lab Rats", attributes = NftMetadata.parseAttributes("eyes=laser, fur: gold"),
        )!!
        assertEquals("art.v0", json["nftType"]!!.jsonPrimitive.content)
        assertEquals("ipfs://$cid", json["video"]!!.jsonPrimitive.content)
        assertNull(json["image"])
        assertEquals("Lab Rats", (json["collection"] as JsonObject)["name"]!!.jsonPrimitive.content)
        val attrs = json["attributes"] as JsonArray
        assertEquals(2, attrs.size)
        assertEquals("gold", (attrs[1] as JsonObject)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun openSeaAlwaysHasAnImage() {
        val json = NftMetadata.build(MetadataStandard.OPENSEA, "Song", "", "ipfs://$cid", "audio/mpeg")!!
        assertEquals("ipfs://$cid", json["image"]!!.jsonPrimitive.content)
        assertEquals("ipfs://$cid", json["animation_url"]!!.jsonPrimitive.content)
        assertNull(NftMetadata.build(MetadataStandard.MEDIA_ONLY, "x", "", "ipfs://$cid", "image/png"))
    }

    @Test
    fun gatewayUrls() {
        assertEquals("https://ipfs.io/ipfs/$cid/1.json", IpfsGateway.toHttp("ipfs://$cid/1.json"))
        assertEquals("https://ipfs.io/ipfs/$cid", IpfsGateway.toHttp(cid))
        assertEquals("https://example.com/a.png", IpfsGateway.toHttp("https://example.com/a.png"))
        assertNull(IpfsGateway.toHttp("not a link"))
        assertTrue(IpfsGateway.looksLikeCid("QmNpi8rcXEkohca8iXu7zysKKSJYqCvBJn3xJwga8jXqWU"))
    }
}
