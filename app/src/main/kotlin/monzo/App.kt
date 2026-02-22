package monzo

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("--- Starting Monzo Transaction Processor ---")
    
    val apiClient = OkHttpApiClient()
    val processor = TransactionProcessor(apiClient)

    try {
        val report = processor.generateReport()
        
        println("\n--- Final Report ---")
        println("Net total across all transactions (deposits - withdrawals): ${report.netTotal}")
        
        println("Accounts with a negative balance: ${report.accountsWithNegativeBalance.size}")
        report.accountsWithNegativeBalance.forEach { acc ->
            println("   - $acc")
        }
        
        println("Successfully processed transactions: ${report.successfulTransactions}")
        
        println("Failed to process transactions: ${report.failedTransactions}")
        report.failureLogs.forEach { log ->
            println("   - $log")
        }
        println("------------------------------------")
        
    } catch(e: Exception) {
        System.err.println("\nFatal error during processing:")
        e.printStackTrace()
    }
}
