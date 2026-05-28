package com.lin.hippyagent.core.agent.collaboration

import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TriggerTicket(
    val ticketId: String,
    val agentId: String,
    val groupId: String,
    val sessionLockId: String,
    val priority: TriggerPriority,
    val acquiredAt: Long,
    val timeoutMs: Long = 30_000L
)

enum class TriggerPriority {
    USER_DIRECT, AI_MENTION, USER_BROADCAST, AI_SILENT
}

sealed class AcquireResult {
    data class Granted(val ticket: TriggerTicket) : AcquireResult()
    data class Denied(val reason: String, val lockedBy: String, val message: String) : AcquireResult()
}

class ParallelArbitrator {

    private val activeTickets = ConcurrentHashMap<String, MutableSet<String>>()
    private val lockTimestamps = ConcurrentHashMap<String, Long>()
    private val sessionLocks = ConcurrentHashMap<String, String>()
    private val ticketToSessionLock = ConcurrentHashMap<String, String>()
    private val ticketPriorities = ConcurrentHashMap<String, TriggerPriority>()

    private fun canPreempt(incoming: TriggerPriority, current: TriggerPriority): Boolean {
        return when (incoming) {
            TriggerPriority.USER_DIRECT -> true
            TriggerPriority.USER_BROADCAST -> current == TriggerPriority.AI_MENTION || current == TriggerPriority.AI_SILENT
            TriggerPriority.AI_MENTION -> current == TriggerPriority.AI_SILENT
            TriggerPriority.AI_SILENT -> false
        }
    }

    fun acquire(
        agentId: String,
        groupId: String,
        sessionLockId: String,
        priority: TriggerPriority
    ): AcquireResult {
        if (lockTimestamps.size > 1000) {
            evictExpired()
        }

        val existingHolder = sessionLocks[sessionLockId]
        if (existingHolder != null && existingHolder != agentId) {
            val currentHolderTicketId = ticketToSessionLock.entries
                .firstOrNull { it.value == sessionLockId }?.key
            val currentPriority = currentHolderTicketId?.let { ticketPriorities[it] }
                ?: TriggerPriority.AI_SILENT

            return if (canPreempt(priority, currentPriority)) {
                Timber.d("${priority.name} preemption: $agentId takes lock from $existingHolder (was ${currentPriority.name}) on session $sessionLockId")
                releaseForSession(sessionLockId)
                AcquireResult.Granted(TriggerTicket(
                    ticketId = UUID.randomUUID().toString(),
                    agentId = agentId,
                    groupId = groupId,
                    sessionLockId = sessionLockId,
                    priority = priority,
                    acquiredAt = System.currentTimeMillis()
                ))
            } else {
                AcquireResult.Denied(
                    reason = "SESSION_LOCKED",
                    lockedBy = existingHolder,
                    message = "Session $sessionLockId is locked by $existingHolder (priority ${currentPriority.name}, cannot preempt with ${priority.name})"
                )
            }
        }

        val ticket = TriggerTicket(
            ticketId = UUID.randomUUID().toString(),
            agentId = agentId,
            groupId = groupId,
            sessionLockId = sessionLockId,
            priority = priority,
            acquiredAt = System.currentTimeMillis()
        )

        activeTickets.getOrPut(groupId) { ConcurrentHashMap.newKeySet() }.add(ticket.ticketId)
        lockTimestamps[ticket.ticketId] = ticket.acquiredAt
        sessionLocks[sessionLockId] = agentId
        ticketToSessionLock[ticket.ticketId] = sessionLockId
        ticketPriorities[ticket.ticketId] = priority

        Timber.d("Ticket acquired: ${ticket.ticketId} agent=$agentId group=$groupId priority=$priority")
        return AcquireResult.Granted(ticket)
    }

    fun release(ticketId: String) {
        activeTickets.values.forEach { it.remove(ticketId) }
        lockTimestamps.remove(ticketId)
        ticketPriorities.remove(ticketId)

        val sessionLockId = ticketToSessionLock.remove(ticketId)
        if (sessionLockId != null) {
            val stillHeld = ticketToSessionLock.values.contains(sessionLockId)
            if (!stillHeld) {
                sessionLocks.remove(sessionLockId)
            }
        }

        Timber.d("Ticket released: $ticketId")
    }

    fun releaseForSession(sessionLockId: String) {
        val ticketsToRelease = ticketToSessionLock.entries
            .filter { it.value == sessionLockId }
            .map { it.key }

        ticketsToRelease.forEach { ticketId ->
            activeTickets.values.forEach { it.remove(ticketId) }
            lockTimestamps.remove(ticketId)
            ticketPriorities.remove(ticketId)
            ticketToSessionLock.remove(ticketId)
        }
        sessionLocks.remove(sessionLockId)

        Timber.d("Released ${ticketsToRelease.size} tickets for session: $sessionLockId")
    }

    fun evictExpired() {
        val now = System.currentTimeMillis()
        val expiredTicketIds = lockTimestamps.entries
            .filter { now - it.value >= 30_000L }
            .map { it.key }

        expiredTicketIds.forEach { ticketId ->
            activeTickets.values.forEach { it.remove(ticketId) }
            lockTimestamps.remove(ticketId)
            ticketPriorities.remove(ticketId)

            val sessionLockId = ticketToSessionLock.remove(ticketId)
            if (sessionLockId != null) {
                val stillHeld = ticketToSessionLock.values.contains(sessionLockId)
                if (!stillHeld) {
                    sessionLocks.remove(sessionLockId)
                }
            }
        }

        if (expiredTicketIds.isNotEmpty()) {
            Timber.d("Evicted ${expiredTicketIds.size} expired tickets")
        }
    }

    fun cleanup(groupId: String) {
        val tickets = activeTickets.remove(groupId) ?: emptySet()
        tickets.forEach { ticketId ->
            lockTimestamps.remove(ticketId)
            ticketPriorities.remove(ticketId)
            val sessionLockId = ticketToSessionLock.remove(ticketId)
            if (sessionLockId != null) {
                val stillHeld = ticketToSessionLock.values.contains(sessionLockId)
                if (!stillHeld) {
                    sessionLocks.remove(sessionLockId)
                }
            }
        }
        Timber.d("Cleaned up arbitration state for group: $groupId (${tickets.size} tickets)")
    }
}
