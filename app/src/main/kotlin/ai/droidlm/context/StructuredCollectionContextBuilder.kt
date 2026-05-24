package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

internal object StructuredCollectionContextBuilder {
    data class Item(
        val primaryLabel: String,
        val entityType: String,
        val type: Any? = null,
        val primaryLabelNodeId: String? = null,
        val tapTargetNodeId: String? = null,
        val fallbackGestureNodeId: String? = null,
        val labelBounds: Rect? = null,
        val rowBounds: Rect? = null,
        val index: Int = 0,
        val confidence: Double = 0.6,
        val accessoryActions: List<AccessoryAction> = emptyList()
    ) {
        val actionability: String
            get() = when {
                tapTargetNodeId != null -> "OPEN_ROW"
                fallbackGestureNodeId != null -> "TEXT_GESTURE"
                else -> "TEXT_ONLY"
            }
    }

    data class AccessoryAction(
        val action: String,
        val label: String,
        val nodeId: String? = null
    )

    fun build(
        source: String,
        collectionType: String,
        items: List<Item>,
        goal: String? = null,
        selectedLabel: String? = null
    ): JSONArray {
        if (items.isEmpty()) return JSONArray()
        val normalizedGoal = normalize(goal)
        val normalizedSelectedLabel = normalize(selectedLabel)
        val scored = items.map { item -> item to goalScore(normalizedGoal, item.primaryLabel) }
        val sortedScores = scored.map { it.second }.sortedDescending()
        val topScore = sortedScores.firstOrNull() ?: 0.0
        val secondScore = sortedScores.drop(1).firstOrNull() ?: 0.0
        return JSONArray().put(
            JSONObject()
                .put("source", source)
                .put("type", collectionType)
                .put("itemCount", items.size)
                .put("items", JSONArray(items.mapIndexed { index, item -> item.toJson(index, normalizedGoal) }))
                .put(
                    "selectionState",
                    JSONObject()
                        .put("selectedLabel", selectedLabel ?: JSONObject.NULL)
                        .put(
                            "selectedNormalizedLabel",
                            if (normalizedSelectedLabel.isBlank()) JSONObject.NULL else normalizedSelectedLabel
                        )
                )
                .put(
                    "ambiguity",
                    JSONObject()
                        .put("goalProvided", normalizedGoal.isNotBlank())
                        .put("topGoalScore", topScore)
                        .put("secondGoalScore", secondScore)
                        .put("closeTopCandidates", topScore > 0.0 && secondScore > 0.0 && topScore - secondScore < CLOSE_SCORE_GAP)
                        .put("nonActionableItemCount", items.count { it.tapTargetNodeId == null })
                        .put("textGestureFallbackCount", items.count { it.actionability == "TEXT_GESTURE" })
                        .put("duplicateNormalizedLabelCount", duplicateNormalizedLabelCount(items))
                )
        )
    }

    fun itemFromNode(
        label: String,
        entityType: String,
        node: UiNode,
        state: PortalState,
        index: Int,
        type: Any? = null,
        confidence: Double = 0.6,
        accessoryActions: List<AccessoryAction> = accessoryActionsFor(label, state.nodes)
    ): Item {
        val directTarget = node.tapTargetNodeId()
        val inferredTarget = directTarget ?: inferAncestorTapTarget(node, state.nodes) ?: inferSpatialTapTarget(node, state.nodes)
        return Item(
            primaryLabel = label,
            entityType = entityType,
            type = type,
            primaryLabelNodeId = node.nodeId,
            tapTargetNodeId = inferredTarget,
            fallbackGestureNodeId = node.nodeId.takeIf { inferredTarget == null && node.bounds != null },
            labelBounds = node.bounds,
            rowBounds = inferredTarget?.let { targetId -> state.nodes.firstOrNull { it.nodeId == targetId }?.bounds } ?: node.bounds,
            index = index,
            confidence = confidence,
            accessoryActions = accessoryActions
        )
    }

    fun legacyVisibleEntity(item: Item, titleKey: String = "title"): JSONObject = item.toLegacyVisibleEntity(titleKey)

    private fun Item.toLegacyVisibleEntity(titleKey: String = "title"): JSONObject = JSONObject()
        .put(titleKey, primaryLabel)
        .put("type", type ?: JSONObject.NULL)
        .put("nodeId", tapTargetNodeId ?: JSONObject.NULL)
        .put("labelNodeId", primaryLabelNodeId ?: JSONObject.NULL)
        .put("fallbackGestureNodeId", fallbackGestureNodeId ?: JSONObject.NULL)
        .put("tappable", tapTargetNodeId != null)
        .put("actionability", actionability)
        .put("confidence", confidence)

    private fun Item.toJson(position: Int, normalizedGoal: String): JSONObject {
        val score = goalScore(normalizedGoal, primaryLabel)
        return JSONObject()
            .put("index", index.takeIf { it > 0 } ?: position)
            .put("entityType", entityType)
            .put("type", type ?: JSONObject.NULL)
            .put("primaryLabel", primaryLabel)
            .put("normalizedLabel", normalize(primaryLabel))
            .put("primaryLabelNodeId", primaryLabelNodeId ?: JSONObject.NULL)
            .put("tapTargetNodeId", tapTargetNodeId ?: JSONObject.NULL)
            .put("fallbackGestureNodeId", fallbackGestureNodeId ?: JSONObject.NULL)
            .put("actionability", actionability)
            .put("actionabilityReason", actionabilityReason)
            .put("safeActions", JSONArray(safeActions))
            .put("goalOverlapScore", score)
            .put("exactGoalTermMatches", JSONArray(exactTermMatches(normalizedGoal, primaryLabel)))
            .put("missingGoalTerms", JSONArray(missingGoalTerms(normalizedGoal, primaryLabel)))
            .put("bounds", boundsJson(rowBounds))
            .put("primaryLabelBounds", boundsJson(labelBounds))
            .put("confidence", confidence)
            .put("accessoryActions", JSONArray(accessoryActions.map { it.toJson() }))
    }

    private val Item.actionabilityReason: String
        get() = when (actionability) {
            "OPEN_ROW" -> "Open/select through the row tap target node."
            "TEXT_GESTURE" -> "Only the label is grounded; use this only when the user clearly named this item and no row target exists."
            else -> "Visible text is not safely actionable; search, scroll, or ask before mutating."
        }

    private val Item.safeActions: List<String>
        get() = when (actionability) {
            "OPEN_ROW" -> listOf("open", "select")
            "TEXT_GESTURE" -> listOf("find_text", "gesture_tap_label")
            else -> listOf("find_text", "search_accessibility_content")
        }

    private fun AccessoryAction.toJson(): JSONObject = JSONObject()
        .put("action", action)
        .put("label", label)
        .put("nodeId", nodeId ?: JSONObject.NULL)

    private fun accessoryActionsFor(label: String, nodes: List<UiNode>): List<AccessoryAction> {
        val normalizedLabel = normalize(label)
        if (normalizedLabel.isBlank()) return emptyList()
        return nodes.asSequence()
            .filter { it.visible && it.enabled }
            .mapNotNull { node ->
                val raw = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim()
                val lower = raw.lowercase()
                when {
                    lower.startsWith("more actions for ") && normalize(lower.removePrefix("more actions for ")) == normalizedLabel ->
                        AccessoryAction("more_actions", raw, node.tapTargetNodeId() ?: node.nodeId)
                    lower.startsWith("more options for ") && normalize(lower.removePrefix("more options for ")) == normalizedLabel ->
                        AccessoryAction("more_actions", raw, node.tapTargetNodeId() ?: node.nodeId)
                    else -> null
                }
            }
            .distinctBy { it.action to it.nodeId }
            .take(MAX_ACCESSORY_ACTIONS)
            .toList()
    }

    private fun inferAncestorTapTarget(node: UiNode, nodes: List<UiNode>): String? {
        val byId = nodes.mapNotNull { candidate -> candidate.nodeId?.let { it to candidate } }.toMap()
        var current = node.parentId?.let(byId::get)
        var hops = 0
        while (current != null && hops < MAX_PARENT_HOPS) {
            current.tapTargetNodeId()?.let { return it }
            current = current.parentId?.let(byId::get)
            hops += 1
        }
        return null
    }

    private fun inferSpatialTapTarget(node: UiNode, nodes: List<UiNode>): String? {
        val labelBounds = node.bounds ?: return null
        return nodes.asSequence()
            .filter { it.visible && it.enabled && it.nodeId != node.nodeId }
            .mapNotNull { candidate ->
                val target = candidate.tapTargetNodeId() ?: return@mapNotNull null
                val bounds = candidate.bounds ?: return@mapNotNull null
                if (looksLikeAccessory(candidate)) return@mapNotNull null
                val score = spatialScore(labelBounds, bounds)
                if (score <= 0.0) null else target to score
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    private fun spatialScore(label: Rect, candidate: Rect): Double {
        if (candidate.contains(label)) return 1.0
        val verticalOverlap = overlap(label.top, label.bottom, candidate.top, candidate.bottom)
        val horizontalOverlap = overlap(label.left, label.right, candidate.left, candidate.right)
        val labelHeight = max(1, label.height())
        val labelWidth = max(1, label.width())
        val verticalRatio = verticalOverlap.toDouble() / labelHeight.toDouble()
        val horizontalRatio = horizontalOverlap.toDouble() / labelWidth.toDouble()
        if (verticalRatio < 0.55 || horizontalRatio < 0.25) return 0.0
        return (verticalRatio * 0.7) + (horizontalRatio * 0.3)
    }

    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)

    private fun UiNode.tapTargetNodeId(): String? {
        if (availableActions.any { it.droidLmAction == "TAP_NODE" }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == "TAP_NODE" }?.targetNodeId
            ?: nodeId.takeIf { clickable }
    }

    private fun looksLikeAccessory(node: UiNode): Boolean {
        val label = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ").lowercase()
        return label.contains("more action") || label.contains("more option") || label.contains("overflow")
    }

    private fun goalScore(normalizedGoal: String, label: String): Double {
        if (normalizedGoal.isBlank()) return 0.0
        val normalizedLabel = normalize(label)
        if (normalizedLabel.isBlank()) return 0.0
        if (normalizedGoal == normalizedLabel) return 1.0
        if (normalizedGoal.contains(normalizedLabel) || normalizedLabel.contains(normalizedGoal)) return 0.92
        val goalTokens = normalizedGoal.split(' ').filter { it.length >= 2 }
        val labelTokens = normalizedLabel.split(' ').filter { it.length >= 2 }
        if (goalTokens.isEmpty() || labelTokens.isEmpty()) return 0.0
        val matched = goalTokens.count { token -> labelTokens.any { it == token || it.contains(token) || token.contains(it) } }
        return matched.toDouble() / goalTokens.size.toDouble()
    }

    private fun exactTermMatches(normalizedGoal: String, label: String): List<String> {
        if (normalizedGoal.isBlank()) return emptyList()
        val labelTokens = normalize(label).split(' ').toSet()
        return normalizedGoal.split(' ').filter { it.length >= 2 && it in labelTokens }
    }

    private fun missingGoalTerms(normalizedGoal: String, label: String): List<String> {
        if (normalizedGoal.isBlank()) return emptyList()
        val labelTokens = normalize(label).split(' ').toSet()
        return normalizedGoal.split(' ').filter { it.length >= 3 && it !in labelTokens }.distinct()
    }

    private fun duplicateNormalizedLabelCount(items: List<Item>): Int = items
        .groupingBy { normalize(it.primaryLabel) }
        .eachCount()
        .count { it.value > 1 }

    private fun boundsJson(rect: Rect?): Any = rect?.let {
        JSONObject()
            .put("left", it.left)
            .put("top", it.top)
            .put("right", it.right)
            .put("bottom", it.bottom)
            .put("width", it.width())
            .put("height", it.height())
    } ?: JSONObject.NULL

    private fun normalize(value: String?): String = value
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()

    private const val MAX_PARENT_HOPS = 6
    private const val MAX_ACCESSORY_ACTIONS = 4
    private const val CLOSE_SCORE_GAP = 0.18
}
