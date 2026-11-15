package com.wear.wearbuddy.data

data class GrowthPlan(
    val level: Int,
    val levelName: String,
    val completed: Int,
    val total: Int,
    val energy: Int,
    val tasks: List<GrowthTask>
)

data class GrowthTask(
    val title: String,
    val status: String,
    val locked: Boolean,
    val current: Int,
    val target: Int,
    val energy: Int,
    val code: String,
    val type: String,
    val hasReward: Boolean
) {
    val canAccept: Boolean
        get() = !locked && code.isNotBlank() && code != "first_buddy" &&
            type.isNotBlank() && type !in setOf("auto", "beginner") && status == "not_accepted"
    val canClaim: Boolean
        get() = !locked && code.isNotBlank() && code != "first_buddy" && hasReward && status == "completed"
}
