package monzo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class TransactionsResponse(
    val ids: List<String> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class TransactionResponse(
    val id: String,
    @SerialName("source_account") val sourceAccount: String,
    @SerialName("target_account") val targetAccount: String,
    val amount: String
)

data class Report(
    val netTotal: BigDecimal,
    val accountsWithNegativeBalance: Set<String>,
    val successfulTransactions: Int,
    val failedTransactions: Int,
    val failureLogs: List<String> = emptyList()
)
