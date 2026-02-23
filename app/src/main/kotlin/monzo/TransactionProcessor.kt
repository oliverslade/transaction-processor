package monzo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal

/**
 * Processor responsible for traversing the API concurrently, aggregating transaction data, 
 * and building the final Report. Math operations resolve sequentially for correctness.
 */
class TransactionProcessor(private val apiClient: ApiClient) {

    companion object {
        private const val CONCURRENCY_LIMIT = 50
    }

    private sealed class ProcessingResult {
        data class Success(val txId: String, val response: TransactionResponse) : ProcessingResult()
        data class Failure(val txId: String, val errorMessage: String) : ProcessingResult()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun generateReport(): Report {
        val accountBalances = mutableMapOf<String, BigDecimal>()
        
        var totalDeposits = BigDecimal.ZERO
        var totalWithdrawals = BigDecimal.ZERO
        
        var successfulTransactions = 0
        var failedTransactions = 0
        val failureLogs = mutableListOf<String>()
        
        // Producer fetches pages sequentially and emits individual TX IDs
        val transactionIdFlow = flow {
            var cursor: String? = null
            var hasMore = true

            while (hasMore) {
                val response = try {
                    apiClient.fetchTransactionIds(cursor)
                } catch (e: Exception) {
                    throw RuntimeException("Failed to fetch transaction IDs at cursor $cursor", e)
                }
                
                response.ids.forEach { emit(it) }

                if (cursor == response.nextCursor) {
                    System.err.println("API Error: Received identical next_cursor '$cursor', breaking to prevent infinite loop.")
                    break
                }
                
                cursor = response.nextCursor
                hasMore = response.hasMore && cursor != null
            }
        }

        // Concurrent fetcher maps IDs to their full Details.
        val detailFlow = transactionIdFlow.flatMapMerge(concurrency = CONCURRENCY_LIMIT) { txId ->
            flow {
                try {
                    val txResponse = apiClient.fetchTransaction(txId)
                    emit(ProcessingResult.Success(txId, txResponse))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(ProcessingResult.Failure(txId, e.message ?: "Unknown API error"))
                }
            }
        }

        detailFlow.collect { result ->
            when (result) {
                is ProcessingResult.Failure -> {
                    failedTransactions++
                    failureLogs.add("TX ${result.txId} failed API fetch: ${result.errorMessage}")
                }
                is ProcessingResult.Success -> {
                    val txResponse = result.response

                    val amount = try {
                        BigDecimal(txResponse.amount)
                    } catch (e: NumberFormatException) {
                        failedTransactions++
                        failureLogs.add("TX ${result.txId} has invalid amount format: '${txResponse.amount}'")
                        return@collect
                    }

                    successfulTransactions++

                    // Track money entering the target account as a deposit
                    if (txResponse.targetAccount.isNotBlank()) {
                        totalDeposits = totalDeposits.add(amount)
                        val targetBalance = accountBalances.getOrDefault(txResponse.targetAccount, BigDecimal.ZERO)
                        accountBalances[txResponse.targetAccount] = targetBalance.add(amount)
                    }

                    // Track money leaving the source account as a withdrawal
                    if (txResponse.sourceAccount.isNotBlank()) {
                        totalWithdrawals = totalWithdrawals.add(amount)
                        val sourceBalance = accountBalances.getOrDefault(txResponse.sourceAccount, BigDecimal.ZERO)
                        accountBalances[txResponse.sourceAccount] = sourceBalance.subtract(amount)
                    }
                }
            }
        }

        val netTotal = totalDeposits.subtract(totalWithdrawals)

        val negativeAccounts = accountBalances.entries
            .filter { it.value < BigDecimal.ZERO }
            .map { it.key }
            .toSet()

        return Report(
            netTotal = netTotal,
            accountsWithNegativeBalance = negativeAccounts,
            successfulTransactions = successfulTransactions,
            failedTransactions = failedTransactions,
            failureLogs = failureLogs
        )
    }
}
