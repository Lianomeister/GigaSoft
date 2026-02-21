package com.clockwork.runtime

import com.clockwork.api.Scheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

class RuntimeScheduler(
    private val pluginId: String,
    private val maxTasks: Int = 500,
    private val tickMillis: Long = 50L,
    private val runObserver: (pluginId: String, taskId: String, durationNanos: Long, success: Boolean) -> Unit = { _, _, _, _ -> }
) : Scheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "giga-scheduler-$pluginId").apply { isDaemon = true }
    }
    private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {
        guardTaskBudget(taskId)
        val delay = max(1, periodTicks) * tickMillis
        val future = executor.scheduleAtFixedRate({
            val started = System.nanoTime()
            var success = true
            try {
                block()
            } catch (t: Throwable) {
                success = false
                throw t
            } finally {
                runObserver(pluginId, taskId, System.nanoTime() - started, success)
            }
        }, delay, delay, TimeUnit.MILLISECONDS)
        tasks[taskId] = future
    }

    override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {
        guardTaskBudget(taskId)
        val delay = max(1, delayTicks) * tickMillis
        val future = executor.schedule({
            val started = System.nanoTime()
            var success = true
            try {
                block()
            } catch (t: Throwable) {
                success = false
                throw t
            } finally {
                runObserver(pluginId, taskId, System.nanoTime() - started, success)
                tasks.remove(taskId)
            }
        }, delay, TimeUnit.MILLISECONDS)
        tasks[taskId] = future
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
    fun activeTaskIds(): List<String> = tasks.keys().toList()

    private fun guardTaskBudget(taskId: String) {
        require(!tasks.containsKey(taskId)) { "Task '$taskId' already exists in plugin '$pluginId'" }
        require(tasks.size < maxTasks) { "Task budget exceeded for plugin '$pluginId'" }
    }
}
