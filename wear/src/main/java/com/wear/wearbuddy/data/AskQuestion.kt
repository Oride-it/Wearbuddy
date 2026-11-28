package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject

data class AskOption(
    val value: String,
    val label: String,
    val description: String? = null,
    val kind: String? = null
)

data class AskItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false
)

data class AskQuestion(
    val items: List<AskItem>,
    val requestId: String? = null,
    val rpcId: Any? = null,
    val permission: Boolean = false
)

data class AskPick(val options: List<AskOption> = emptyList(), val custom: String = "") {
    val isEmpty: Boolean get() = options.isEmpty() && custom.isBlank()
}

internal const val ACP_QUESTION = "_codebuddy.ai/question"

internal fun JSONObject.askId(): String? =
    askText("toolCallId", "requestId", "interruptionId")
        ?: optJSONObject("toolCall")?.askText("toolCallId")

internal fun parseAsk(node: JSONObject, rpcId: Any? = null, permission: Boolean = false): AskQuestion? {
    val root = node.optJSONObject("schema") ?: node
    val questions = root.optJSONArray("questions")
    val items = if (questions != null && !permission) {
        List(questions.length()) { index -> questions.optJSONObject(index)?.toAskItem(index) }
            .filterNotNull()
    } else {
        listOfNotNull(root.toAskItem(0, permission))
    }
    return items.takeIf { it.isNotEmpty() }?.let { AskQuestion(it, node.askId(), rpcId, permission) }
}

private fun JSONObject.toAskItem(index: Int, permission: Boolean = false): AskItem? {
    val options = optJSONArray("options")?.let { array ->
        List(array.length()) { array.opt(it) }.mapNotNull { option ->
            when (option) {
                is String -> option.takeIf { it.isNotBlank() }?.let { AskOption(it, it) }
                is JSONObject -> {
                    val label = option.askText("label", "name", "title", "value", "optionId")
                        ?: return@mapNotNull null
                    AskOption(
                        value = option.askText("optionId", "value", "id") ?: label,
                        label = label,
                        description = option.askText("description"),
                        kind = option.askText("kind")
                    )
                }
                else -> null
            }
        }
    }.orEmpty()
    val question = askText("question", "prompt", "title")
        ?: if (permission && options.isNotEmpty()) optJSONObject("toolCall")?.askText("title").orEmpty()
        else return null
    return AskItem(
        id = askText("id") ?: "q-$index",
        question = question,
        header = askText("header"),
        options = options,
        multiSelect = !permission && (optBoolean("multiSelect") || optBoolean("multiple") ||
            askText("type") == "multi" || askText("selectionMode") == "multiple")
    )
}

private fun JSONObject.askText(vararg fields: String): String? =
    fields.firstNotNullOfOrNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }

internal fun AskQuestion.answers(picks: Map<String, AskPick>): JSONObject = JSONObject().apply {
    for (item in items) {
        val pick = picks[item.id] ?: continue
        val values = pick.options.map { it.value }
        val custom = pick.custom.trim().takeIf { it.isNotEmpty() }
        if (item.multiSelect) {
            val allValues = values + listOfNotNull(custom)
            if (allValues.isNotEmpty()) put(item.id, JSONArray(allValues))
        } else {
            (custom ?: values.firstOrNull())?.let { put(item.id, it) }
        }
    }
}

internal fun AskQuestion.answerText(picks: Map<String, AskPick>): String = items.mapNotNull { item ->
    val pick = picks[item.id]?.takeUnless { it.isEmpty } ?: return@mapNotNull null
    val custom = pick.custom.trim().takeIf { it.isNotEmpty() }
    val choices = pick.options.map { it.label }
    val labels = if (item.multiSelect) choices + listOfNotNull(custom)
        else listOfNotNull(custom ?: choices.firstOrNull())
    val answer = labels.joinToString("、")
    if (items.size == 1) answer else "${item.header ?: item.question}：$answer"
}.joinToString("\n")

internal fun AskQuestion.rpcOutcome(picks: Map<String, AskPick>?): JSONObject {
    val outcome = JSONObject()
    when {
        picks == null -> outcome.put("outcome", "cancelled")
        permission -> {
            val option = picks[items.first().id]?.options?.singleOrNull()
                ?: error("No permission option selected")
            outcome.put("outcome", "selected").put("optionId", option.value)
        }
        else -> outcome.put("outcome", "submitted").put("data", JSONObject().put("answers", answers(picks)))
    }
    return JSONObject().put("outcome", outcome)
}
