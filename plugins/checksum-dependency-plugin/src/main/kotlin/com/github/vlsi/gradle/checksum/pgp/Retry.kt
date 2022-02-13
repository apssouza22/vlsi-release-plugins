/*
 * Copyright 2019 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.github.vlsi.gradle.checksum.pgp

import com.github.vlsi.gradle.checksum.info
import java.net.*
import java.time.Duration
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.gradle.api.logging.Logging

private val logger = Logging.getLogger(Retry::class.java)

class RetrySchedule(
    val initialDelay: Long = 100,
    val maximumDelay: Long = 10000
)

abstract class DelayedTask(
    val retrySchedule: RetrySchedule,
    var timestamp: Long
) : Delayed {
    var nextDelay: Long = retrySchedule.initialDelay
    var latency: Long = 0
    var maxTimeout: Long = 500

    override fun getDelay(unit: TimeUnit): Long =
        unit.convert(timestamp - System.currentTimeMillis(), TimeUnit.MILLISECONDS)

    override fun compareTo(other: Delayed?): Int =
        compareValuesBy(this, other as DelayedTask, { it.timestamp }, { it.latency })

    fun reschedule(success: Boolean) {
        if (success) {
            timestamp = 0
            nextDelay = retrySchedule.initialDelay
        } else {
            timestamp =
                System.currentTimeMillis() + nextDelay.coerceAtLeast(retrySchedule.initialDelay)
            nextDelay = retrySchedule.maximumDelay.coerceAtMost(nextDelay * 2)
        }
    }
}

class InetAddressTask(
    retrySchedule: RetrySchedule,
    val uri: URI,
    val inetAddress: InetAddress,
    timestamp: Long
) : DelayedTask(retrySchedule, timestamp) {
    override fun toString(): String {
        return "InetAddressTask(inetAddress=$inetAddress,uri=$uri)"
    }
}

class DnsLookupTask(
    retrySchedule: RetrySchedule,
    private val uri: URI,
    timestamp: Long
) : DelayedTask(retrySchedule, timestamp) {

    fun resolve() =
        InetAddress.getAllByName(uri.host)
            .toMutableList().apply { shuffle() }
            .map { InetAddressTask(retrySchedule, uri, it, timestamp) }

    override fun toString(): String {
        return "DnsLookupTask(uri=$uri)"
    }
}

class RetryException(message: String, val httpCode: Int) : Exception(message)

class ShouldRetrySpec(
    val attempt: Int,
    val retryCount: Int,
    val uri: URI,
    val inetAddress: InetAddress,
    val maxTimeout: Long
) {
    private val conditions = mutableListOf<(Throwable) -> Boolean?>()

    var latency: Long = 0

    fun retryIf(condition: (Throwable) -> Boolean?) =
        conditions.add(condition)

    fun retry(comment: String, httpCode: Int): Nothing = throw RetryException(comment, httpCode)

    fun shouldRetry(throwable: Throwable): Boolean {
        for (condition in conditions) {
            condition(throwable)?.let {
                return it
            }
        }
        return true
    }
}

class Retry(
    uris: List<URI> = listOf(
        URI("https://keys.openpgp.org"),
        URI("https://keyserver.ubuntu.com")
    ),
    val keyResolutionTimeout: Duration = Duration.ofSeconds(40),
    val retrySchedule: RetrySchedule = RetrySchedule(),
    val retryCount: Int = 30,
    val minLoggableTimeout: Duration = Duration.ofSeconds(4)
) {
    private fun <T> Iterable<T>.shuffled(): List<T> = toMutableList().apply { shuffle() }

    private val queue = DelayQueue<DelayedTask>(
        uris.shuffled().map { DnsLookupTask(retrySchedule, it, 0) }
    )

    private fun borrowAddress(deadline: Long): InetAddressTask? {
        while (true) {
            queue.peek()?.let {
                val delay = it.getDelay(TimeUnit.SECONDS)
                if (delay > 1) {
                    logger.info { "Next attempt requires to a delay of $delay seconds. The action is $it" }
                }
            }

            val item = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                ?: return null

            when (item) {
                is InetAddressTask -> return item
                is DnsLookupTask -> try {
                    queue += item.resolve()
                } catch (e: UnknownHostException) {
                    // Retry the host later
                    item.reschedule(false)
                    queue.add(item)
                }
                else -> throw IllegalArgumentException("Unsupported element in DelayQueue: ${item::class}, $item")
            }
        }
    }

    operator fun <T> invoke(description: String, action: ShouldRetrySpec.() -> T): T? {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + keyResolutionTimeout.toMillis()
        var attempt = 0
        var seen404 = false
        while (attempt < retryCount) {
            attempt += 1
            val attemptStart = System.currentTimeMillis()
            val address = borrowAddress(deadline) ?: break
            val spec = ShouldRetrySpec(
                attempt,
                retryCount,
                address.uri,
                address.inetAddress,
                address.maxTimeout
            )
            var success = true
            try {
                logger.info { "$description (attempt $attempt of $retryCount, ${address.inetAddress.hostAddress}, ${address.uri})" }
                return spec.action()
            } catch (error: Error) {
                success = false
                throw error
            } catch (throwable: Throwable) {
                success = false
                when {
                    throwable is RetryException -> {
                        seen404 = seen404 || throwable.httpCode == HttpURLConnection.HTTP_NOT_FOUND
                        logger.lifecycle("Retrying $description (attempt $attempt of $retryCount, ${address.inetAddress.hostAddress}, ${address.uri}): ${throwable.message}")
                    }
                    throwable is ConnectException ||
                            throwable is SocketTimeoutException -> {
                        val attemptDuration = System.currentTimeMillis() - attemptStart
                        val isSevere = attemptDuration >= minLoggableTimeout.toMillis()
                        // Build message only in case it will be printed
                        if (isSevere || logger.isDebugEnabled) {
                            val message =
                                "${throwable::class.simpleName}: $description (timeout ${address.maxTimeout}ms, attempt $attempt of $retryCount, ${address.inetAddress.hostAddress}, ${address.uri})"
                            if (!isSevere) {
                                // Not severe => log debug only
                                logger.debug(message)
                            } else if (logger.isDebugEnabled) {
                                // Debug enabled => log stacktrace as well
                                logger.debug(message, throwable)
                            } else {
                                // Otherwise log to the console
                                logger.lifecycle(message)
                            }
                        }
                        address.maxTimeout =
                            120000L.coerceAtMost((address.maxTimeout * 1.5).toLong())
                    }
                    !spec.shouldRetry(throwable) -> throw throwable
                }
            } finally {
                address.reschedule(success)
                if (success) {
                    address.latency = spec.latency
                }
                queue.add(address)
            }
        }
        if (seen404) {
            logger.lifecycle("Assuming 404 NOT_FOUND for <<$description>> after $attempt iterations and ${System.currentTimeMillis() - startTime}ms")
            return null
        }
        throw TimeoutException("Stopping retry attempts for <<$description>> after $attempt iterations and ${System.currentTimeMillis() - startTime}ms")
    }
}
