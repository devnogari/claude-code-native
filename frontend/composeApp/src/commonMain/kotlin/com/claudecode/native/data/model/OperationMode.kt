package com.claudecode.native.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the permission mode for Claude CLI operations.
 * Matches the backend's permission modes: default, plan, bypassPermissions.
 */
@Serializable
enum class OperationMode {
    /**
     * Default mode - uses --dangerously-skip-permissions for convenience.
     * Most operations execute without prompting.
     */
    @SerialName("default")
    DEFAULT,

    /**
     * Plan mode - uses --permission-mode plan.
     * Claude creates plans that require user review before execution.
     */
    @SerialName("plan")
    PLAN,

    /**
     * Bypass mode - uses --dangerously-skip-permissions.
     * All permission checks are skipped, identical to CLI behavior.
     */
    @SerialName("bypassPermissions")
    BYPASS;

    companion object {
        /**
         * Returns the next mode in the cycle: DEFAULT -> PLAN -> BYPASS -> DEFAULT
         */
        fun OperationMode.next(): OperationMode = when (this) {
            DEFAULT -> PLAN
            PLAN -> BYPASS
            BYPASS -> DEFAULT
        }

        /**
         * Converts a string to OperationMode, defaulting to DEFAULT if unknown.
         * Only accepts exact mode values that match backend constants.
         */
        fun fromString(value: String): OperationMode = when (value.lowercase()) {
            "default" -> DEFAULT
            "plan" -> PLAN
            "bypasspermissions" -> BYPASS
            else -> DEFAULT
        }

        /**
         * Returns the serialized name for sending to server.
         */
        fun OperationMode.toServerValue(): String = when (this) {
            DEFAULT -> "default"
            PLAN -> "plan"
            BYPASS -> "bypassPermissions"
        }
    }

    /**
     * Human-readable display name for UI.
     */
    val displayName: String
        get() = when (this) {
            DEFAULT -> "Auto"
            PLAN -> "Plan"
            BYPASS -> "Bypass"
        }

    /**
     * Description text for UI tooltips/help.
     */
    val description: String
        get() = when (this) {
            DEFAULT -> "Skip all permission checks (auto-approve)"
            PLAN -> "Review plans before execution"
            BYPASS -> "Skip all permission checks"
        }
}
