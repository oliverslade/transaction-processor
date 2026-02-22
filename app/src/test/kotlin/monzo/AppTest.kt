package monzo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

class AppTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var processor: TransactionProcessor

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val apiClient = OkHttpApiClient(baseUrl = baseUrl)
        processor = TransactionProcessor(apiClient)
    }

    @AfterEach
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `processor generates correct report with pagination and simulated failures concurrently`() = runBlocking {
        // A Dispatcher allows MockWebServer to route incoming HTTP requests to specific responses based on the URL path.
        // This is required because our Flow concurrently fetches URLs in an unpredictable order.
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/transactions" -> MockResponse().setBody("""
                        {
                            "ids": ["tx_1", "tx_2"],
                            "next_cursor": "tx_2",
                            "has_more": true
                        }
                    """.trimIndent())
                    
                    path == "/transactions?cursor=tx_2" -> MockResponse().setBody("""
                        {
                            "ids": ["tx_3", "tx_4"],
                            "next_cursor": null,
                            "has_more": false
                        }
                    """.trimIndent())
                    
                    path == "/transactions/tx_1" -> MockResponse().setBody("""
                        {
                            "id": "tx_1",
                            "source_account": "acc_A",
                            "target_account": "acc_B",
                            "amount": "10.00"
                        }
                    """.trimIndent())
                    
                    path == "/transactions/tx_2" -> MockResponse().setBody("""
                        {
                            "id": "tx_2",
                            "source_account": "acc_B",
                            "target_account": "acc_C",
                            "amount": "20.00"
                        }
                    """.trimIndent())
                    
                    path == "/transactions/tx_3" -> MockResponse().setBody("""
                        {
                            "id": "tx_3",
                            "source_account": "acc_C",
                            "target_account": "acc_A",
                            "amount": "50.00"
                        }
                    """.trimIndent())
                    
                    path == "/transactions/tx_4" -> MockResponse().setResponseCode(404).setBody("""
                        {"error": "not found"}
                    """.trimIndent())
                    
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val report = processor.generateReport()

        // Balances: A=40.00, B=-10.00, C=-30.00
        
        assertEquals(3, report.successfulTransactions, "Should have 3 successful transactions")
        assertEquals(1, report.failedTransactions, "Should have 1 failed transaction")

        assertEquals(2, report.accountsWithNegativeBalance.size, "Should flag 2 negative accounts")
        assertTrue(report.accountsWithNegativeBalance.containsAll(listOf("acc_B", "acc_C")))

        assertEquals(BigDecimal("0.00"), report.netTotal.setScale(2))

        // 2 pages + 4 distinct transactions
        assertEquals(6, mockWebServer.requestCount)
    }

    @Test
    fun `processor handles malformed json, empty amounts, missing accounts, and zero balances safely`() = runBlocking {
        // Map edge cases to specific simulated API paths
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/transactions" -> MockResponse().setBody("""
                        {
                            "ids": ["tx_bad_json", "tx_empty", "tx_nan", "tx_single_sided", "tx_zero_run"],
                            "next_cursor": null,
                            "has_more": false
                        }
                    """.trimIndent())
                    
                    // Corrupted API response
                    path == "/transactions/tx_bad_json" -> MockResponse().setBody("""
                        { "id": "tx_bad_json", "source_account": "broken_quote 
                    """.trimIndent())
                    
                    // Empty amount string 
                    path == "/transactions/tx_empty" -> MockResponse().setBody("""
                        {
                            "id": "tx_empty",
                            "source_account": "acc_empty_src",
                            "target_account": "acc_empty_target",
                            "amount": ""
                        }
                    """.trimIndent())
                    
                    // Invalid amount format
                    path == "/transactions/tx_nan" -> MockResponse().setBody("""
                        {
                            "id": "tx_nan",
                            "source_account": "acc_nan_src",
                            "target_account": "acc_nan_target",
                            "amount": "abc123.45"
                        }
                    """.trimIndent())
                    
                    // Money entering system without a source +500 to acc_single
                    path == "/transactions/tx_single_sided" -> MockResponse().setBody("""
                        {
                            "id": "tx_single_sided",
                            "source_account": "",
                            "target_account": "acc_single",
                            "amount": "500.00"
                        }
                    """.trimIndent())
                    
                    // Deposits 500, then withdraws 500. Balance ends exactly at 0.00
                    path == "/transactions/tx_zero_run" -> MockResponse().setBody("""
                        {
                            "id": "tx_zero_run",
                            "source_account": "acc_single",
                            "target_account": "",
                            "amount": "500.00"
                        }
                    """.trimIndent())
                    
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val report = processor.generateReport()

        assertEquals(2, report.successfulTransactions)
        assertEquals(3, report.failedTransactions)
        assertEquals(3, report.failureLogs.size)

        val capturedErrors = report.failureLogs.joinToString()
        assertTrue(capturedErrors.contains("tx_bad_json"))
        assertTrue(capturedErrors.contains("tx_empty"))
        assertTrue(capturedErrors.contains("tx_nan"))

        assertTrue(report.accountsWithNegativeBalance.isEmpty())
        
        assertEquals(BigDecimal("0.00"), report.netTotal.setScale(2))

        assertEquals(6, mockWebServer.requestCount)
    }
}
