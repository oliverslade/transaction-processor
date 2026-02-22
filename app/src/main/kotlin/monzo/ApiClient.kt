package monzo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

/**
 * Interface representing the capability to fetch transactions from the API.
 * This keeps the processor completely decoupled from OkHttp and JSON specifics.
 */
interface ApiClient {
    suspend fun fetchTransactionIds(cursor: String? = null): TransactionsResponse
    suspend fun fetchTransaction(txId: String): TransactionResponse
}

class OkHttpApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(30))
        .build(),
    private val baseUrl: String = "https://api.monzo.com/task-review-interview",
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ApiClient {

    override suspend fun fetchTransactionIds(cursor: String?): TransactionsResponse = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/transactions".toHttpUrl().newBuilder()
        if (cursor != null) {
            urlBuilder.addQueryParameter("cursor", cursor)
        }
        val url = urlBuilder.build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch transaction IDs, HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty response body")
            json.decodeFromString(bodyString)
        }
    }

    override suspend fun fetchTransaction(txId: String): TransactionResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/transactions/$txId"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("HTTP ${response.code} - $errorBody")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty response body")
            try {
                json.decodeFromString<TransactionResponse>(bodyString)
            } catch (e: Exception) {
                throw IOException("JSON matching failed for ID $txId: ${e.message}")
            }
        }
    }
}
