package io.github.sparkcyf.voicesipagent.app

import com.mongodb.ConnectionString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMongoDnsTest {
    private val atlasSrvJson = """
        {"Status":0,"Answer":[{"type":33,"data":"0 0 27017 shard-00-00.cluster.example.com."}]}
    """.trimIndent()

    @Test
    fun parsesSrvRecordInMongoDriverFormat() {
        assertEquals(
            listOf("0 0 27017 shard-00-00.cluster.example.com"),
            AndroidDnsJson.parse(atlasSrvJson, "SRV"),
        )
    }

    @Test
    fun joinsQuotedTxtChunksForAtlasOptions() {
        val json = """{"Status":0,"Answer":[{"type":16,"data":"\"authSource=admin&\" \"replicaSet=atlas-abc\""}]}"""
        assertEquals(
            listOf("authSource=admin&replicaSet=atlas-abc"),
            AndroidDnsJson.parse(json, "TXT"),
        )
    }

    @Test
    fun mongodbSrvConnectionUsesInjectedAndroidDnsAndKeepsAtlasSettings() {
        val calls = mutableListOf<Pair<String, String>>()
        val dns = AndroidDnsClient { name, type ->
            calls += name to type
            when (type) {
                "SRV" -> atlasSrvJson
                "TXT" -> """{"Status":0,"Answer":[{"type":16,"data":"\"authSource=admin\""}]}"""
                else -> error("unexpected DNS type")
            }
        }
        val connection = ConnectionString(
            "mongodb+srv://user:password@cluster.example.com/voicx_sip_prod?retryWrites=true&w=majority",
            dns,
        )

        assertTrue(connection.isSrvProtocol)
        assertEquals("voicx_sip_prod", connection.database)
        assertEquals(true, connection.retryWritesValue)
        assertTrue(calls.contains("_mongodb._tcp.cluster.example.com" to "SRV"))
        assertTrue(calls.contains("cluster.example.com" to "TXT"))
    }
}
