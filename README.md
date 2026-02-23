# Monzo Transaction Processor

A robust, concurrent CLI application built in Kotlin to process and evaluate transaction data.

## The Brief

We'd like you to write a simple program that processes a list of transactions and generates a report.

**Your program should answer the following questions:**
1. What is the net total across all transactions (deposits - withdrawals)?
2. Are there any accounts with a negative balance?
3. How many transactions were successfully processed, and how many could not be processed?

You are welcome to use standard libraries to handle things like making HTTP requests and parsing JSON.

**API spec:**
*   `GET https://api.monzo.com/task-review-interview/transactions` - gets transaction IDs
*   `GET https://api.monzo.com/task-review-interview/transactions?cursor=<LATEST_ID>` - gets transaction IDs, paginated
*   `GET https://api.monzo.com/task-review-interview/transactions/<TX_ID>` - gets a specific transaction

## How to Run

To run this application and its test suite from scratch, you will need Java and Gradle.

1. Install Java 21+ using [SDKMAN!](https://sdkman.io/) (`sdk install java 21-open`), via Homebrew on macOS (`brew install openjdk@21`), or by downloading it directly from Adoptium/Oracle.
2. Install Gradle using [SDKMAN!](https://sdkman.io/) (`sdk install gradle`), or via Homebrew (`brew install gradle`).

*(Note: If you have Java installed, you can skip installing Gradle globally. The included `./gradlew` wrapper script will automatically download and configure the exact Gradle version required by the project).*

Once the prerequisites are installed, open your terminal, navigate to the root directory of the project, and run the following commands:

*   **To run the test suite**: 
    `./gradlew test`
*   **To run the application**: 
    `./gradlew run`

## Architecture & Patterns

This solution prioritises readable code and a clear separation of concerns. Instead of reaching for a heavy application framework like Spring Boot, I've implemented the core logic using standard Kotlin libraries.

The main engine is built around a concurrent Kotlin Coroutines `Flow` pipeline. A producer pulls pages of transaction IDs sequentially from the API, and then passes them to a worker pool. This pool uses `flatMapMerge` to spin off up to 50 concurrent requests at a time, fetching the individual transaction details without blocking. 

Once those concurrent requests return, a final `.collect` operator pulls everything back into a single sequential thread. This is a deliberate choice: it guarantees our math operations are thread-safe and saves us from having to write complex locks or atomic wrappers around the data.

For the models, we map the JSON payloads directly onto simple Kotlin data classes using `kotlinx.serialization`. To avoid floating point precision issues, monetary amounts are processed as pure strings during the network hop and only converted to `BigDecimal` during the final math aggregation. Everything is glued together using manual constructor injection, which makes the whole pipeline incredibly easy to test against a `MockWebServer`.

## Breaking Points at Massive Scale

The current pipeline breezes through several thousand transactions in a few seconds, but it would definitely fall over if we scaled up to millions of records.

The biggest constraint is memory. Right now, the app keeps a running tally of every unique account balance in an in-memory hash map. With millions of distinct accounts, that map would eventually eat up the entire heap space and crash the JVM. 

Secondly, blasting 50 concurrent requests at a single API without pause is going to trip aggressive rate limiters (HTTP 429s) if the upstream service isn't expecting it. Finally, because this is just a single local script, there's no fault tolerance if your internet drops out halfway through processing a million rows, you lose all your progress and have to start over.

## Future Scaling Solutions

If we needed to scale, we'd have to move away from a local script entirely and adopt a distributed architecture.

The first step would be breaking the pipeline in two. Our initial ID fetcher would just become a rapid scraper that publishes chunks of IDs onto a message broker like Kafka or AWS SQS. From there, a pool of stateless microservice workers would consume from the queue, fetch the details, and do the math. 

Instead of tracking balances in local memory, those workers would fire atomic update queries to a persistent datastore, like Redis or Postgres. This gives us horizontal scaling; instead of running 50 coroutines on one laptop, we could run 50 pods in the cloud. It also unlocks resilience if a worker node dies, the queue just hands the transaction to the next available worker, meaning we never lose data midway through a run.
