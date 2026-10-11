package com.ngocsi.music

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NgocSiAiChatTurn(
    val role: String,
    val content: String
)

internal data class NgocSiAiBackendReply(
    val answer: String,
    val remainingToday: Int?,
    val remainingGlobalToday: Int?
)

/**
 * Creates a bounded, alternating user/assistant history for the callable contract.
 * The most recent user prompt is kept; incomplete failed user turns are collapsed.
 * At most 5 turns are sent and the total payload is at most 8,000 characters.
 */
internal object NgocSiAiBackendRequest {
    fun normalize(messages: List<NgocSiAiChatTurn>): List<NgocSiAiChatTurn> {
        val alternating = mutableListOf<NgocSiAiChatTurn>()
        for (message in messages) {
            val role = message.role.trim()
            if (role != "user" && role != "assistant") continue
            val content = message.content.trim()
            if (content.isEmpty()) continue

            when (role) {
                "user" -> {
                    val bounded = content.take(2_000)
                    if (alternating.lastOrNull()?.role == "user") {
                        // Replace an earlier failed prompt rather than sending consecutive user roles.
                        alternating[alternating.lastIndex] = NgocSiAiChatTurn("user", bounded)
                    } else {
                        alternating.add(NgocSiAiChatTurn("user", bounded))
                    }
                }
                "assistant" -> {
                    if (alternating.lastOrNull()?.role == "user") {
                        alternating.add(NgocSiAiChatTurn("assistant", content.take(1_500)))
                    }
                }
            }
        }

        // The callable requires the final message to be a user message.
        while (alternating.lastOrNull()?.role != "user" && alternating.isNotEmpty()) {
            alternating.removeAt(alternating.lastIndex)
        }
        while (alternating.firstOrNull()?.role != "user" && alternating.isNotEmpty()) {
            alternating.removeAt(0)
        }
        if (alternating.isEmpty()) return emptyList()

        // An alternating sequence that ends in user has odd length; taking 5 preserves that shape.
        val recent = alternating.takeLast(5).toMutableList()
        if (recent.firstOrNull()?.role != "user" || recent.lastOrNull()?.role != "user") {
            return emptyList()
        }

        // Leave room for a 2,000-character current prompt while keeping history under 8,000 chars.
        return recent.mapIndexed { index, turn ->
            val limit = if (index == recent.lastIndex) 2_000 else 1_500
            turn.copy(content = turn.content.take(limit))
        }.takeIf { it.sumOf(NgocSiAiChatTurn::contentLength) <= 8_000 }.orEmpty()
    }

    private fun NgocSiAiChatTurn.contentLength(): Int = content.length

    fun toCallableData(messages: List<NgocSiAiChatTurn>): Map<String, Any> {
        val history = normalize(messages)
        require(history.isNotEmpty()) { "NGOC_SI_AI_EMPTY_HISTORY" }
        return mapOf(
            "messages" to history.map { turn ->
                mapOf("role" to turn.role, "content" to turn.content)
            }
        )
    }
}

/**
 * Client for the server-side Gemini proxy. It contains no provider key.
 * Enable use only after the callable, Anonymous Auth, and App Check are configured.
 */
internal class NgocSiAiBackendClient(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-southeast1")
) {
    suspend fun send(messages: List<NgocSiAiChatTurn>): NgocSiAiBackendReply {
        val payload = NgocSiAiBackendRequest.toCallableData(messages)
        if (auth.currentUser == null) {
            auth.signInAnonymously().awaitResult()
        }
        if (auth.currentUser == null) {
            throw IllegalStateException("NGOC_SI_AI_AUTH_NOT_READY")
        }

        val result = functions
            .getHttpsCallable("ngocSiAiChat")
            .call(payload)
            .awaitResult()

        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("NGOC_SI_AI_INVALID_RESPONSE")
        val answer = (data["answer"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("NGOC_SI_AI_EMPTY_RESPONSE")
        return NgocSiAiBackendReply(
            answer = answer,
            remainingToday = (data["remainingToday"] as? Number)?.toInt(),
            remainingGlobalToday = (data["remainingGlobalToday"] as? Number)?.toInt()
        )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("FIREBASE_TASK_FAILED")
                )
            }
        }
    }
