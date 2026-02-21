package com.clockwork.runtime

import com.clockwork.api.Scheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

class RuntimeScheduler(
    private val pluginId: String,
    private val maxTasks: Int = 500,
    private val workerThreads: Int = 1,
    private val tickMillis: Long = 50L,
    private val runObserver: (pluginId: String, taskId: String, durationNanos: Long, success: Boolean) -> Unit = { _, _, _, _ -> }
) : Scheduler {
    init {
        require(workerThreads > 0) { "workerThreads must be > 0 for plugin '$pluginId'" }
    }

    private val executor: ScheduledExecutorService = ScheduledThreadPoolExecutor(workerThreads) { r ->
        Thread(r, "giga-scheduler-$pluginId").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val registrationLock = Any()

    override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {
        registerTask(taskId) {
            val delay = max(1, periodTicks) * tickMillis
            executor.scheduleAtFixedRate({
                val started = System.nanoTime()
                var success = true
                try {
                    block()
                } catch (_: Exception) {
                    success = false
                } finally {
                    runObserver(pluginId, taskId, System.nanoTime() - started, success)
                }
            }, delay, delay, TimeUnit.MILLISECONDS)
        }
    }

    override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {
        registerTask(taskId) {
            val delay = max(1, delayTicks) * tickMillis
            executor.schedule({
                val started = System.nanoTime()
                var success = true
                try {
                    block()
                } catch (_: Exception) {
                    success = false
                } finally {
                    runObserver(pluginId, taskId, System.nanoTime() - started, success)
                    tasks.remove(taskId)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    override fun cancel(taskId: String) {
        tasks.remove(taskId)?.cancel(false)
    }

    override fun clear() {
        tasks.values.forEach { it.cancel(false) }
        tasks.clear()
    }

    fun shutdown() {
        clear()
        executor.shutdownNow()
    }

    fun activeTaskCount(): Int = tasks.size
    fun activeTaskIds(): List<String> = tasks.keys().toList().sorted()

    private fun registerTask(taskId: String, schedule: () -> ScheduledFuture<*>): ScheduledFuture<*> {
        synchronized(registrationLock) {
            require(!tasks.containsKey(taskId)) { "Task '$taskId' already exists in plugin '$pluginId'" }
            require(tasks.size < maxTasks) { "Task budget exceeded for plugin '$pluginId'" }
            val future = schedule()
            tasks[taskId] = future
            return future
        }
    }
}
