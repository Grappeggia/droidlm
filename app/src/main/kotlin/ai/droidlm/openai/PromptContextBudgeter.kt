package ai.droidlm.openai

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.relay.ActiveApp
import ai.droidlm.relay.DeviceContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object PromptContextBudgeter {
    const val DEFAULT_CONTEXT_TOKEN_BUDGET = 120_000
    const val STRICT_CONTEXT_TOKEN_BUDGET = 80_000

    private const val CHARS_PER_TOKEN = 4
    private const val MAX_TEXT_CHARS = 512
    private const val MAX_KERNEL_TEXT_CHARS = 2_048
    private const val MAX_SUMMARY_TEXT_CHARS = 1_024
    private const val MAX_OMITTED_PATHS = 80

    private val tierWeights = linkedMapOf(
        PromptTier.INTERACTION_KERNEL to 10,
        PromptTier.VISIBLE_ACTIONABLE to 30,
        PromptTier.GOAL_RELEVANT_VISIBLE to 20,
        PromptTier.NAVIGATION_STRUCTURE to 20,
        PromptTier.ADJACENT_CONTEXT to 15,
        PromptTier.SUMMARY_REMAINDER to 5
    )

    fun build(
        goal: String,
        activeApp: ActiveApp?,
        deviceContext: DeviceContext?,
        uiState: PortalState?,
        packages: List<AppPackage>,
        history: List<String> = emptyList(),
        lastResults: JSONArray = JSONArray(),
        doNotRepeat: JSONArray = JSONArray(),
        targetContextTokens: Int = DEFAULT_CONTEXT_TOKEN_BUDGET
    ): BudgetedPromptContext {
        val targetChars = targetContextTokens * CHARS_PER_TOKEN
        val tierBudgets = tierWeights.mapValues { (_, weight) -> (targetChars * weight) / 100 }
        val omitted = mutableListOf<String>()
        val originalChars = JSONObject()
            .put("deviceContext", deviceContext?.toFullJson()?.jsonChars() ?: 0)
            .put("uiState", uiState?.toCompactSourceJson()?.jsonChars() ?: 0)
            .put("installedPackages", JSONArray(packages.map { it.toPromptJson() }).jsonChars())

        val goalTokens = goalTokens(goal)
        val selectedByTier = linkedMapOf<PromptTier, MutableList<PromptCandidate>>()
        PromptTier.entries.forEach { selectedByTier[it] = mutableListOf() }
        val seen = mutableSetOf<String>()

        val interactionKernel = buildInteractionKernel(goal, activeApp, deviceContext, uiState, history, lastResults, doNotRepeat)
        val kernelCandidate = PromptCandidate(
            tier = PromptTier.INTERACTION_KERNEL,
            source = "interaction_kernel",
            role = "state",
            text = null,
            nodeId = null,
            score = Int.MAX_VALUE,
            json = interactionKernel
        )
        selectedByTier.getValue(PromptTier.INTERACTION_KERNEL).add(kernelCandidate)
        seen += kernelCandidate.dedupeKey

        collectPortalCandidates(uiState, goalTokens).forEach { addCandidate(it, selectedByTier, seen) }
        collectDeviceContextCandidates(deviceContext, goalTokens).forEach { addCandidate(it, selectedByTier, seen) }
        collectPackageCandidates(packages, goalTokens).forEach { addCandidate(it, selectedByTier, seen) }
        collectSummaryCandidates(deviceContext, uiState, packages).forEach { addCandidate(it, selectedByTier, seen) }

        val tierJson = JSONObject()
        val tierStats = JSONArray()
        selectedByTier.forEach { (tier, candidates) ->
            val budget = tierBudgets.getValue(tier)
            val packed = packTier(tier, candidates, budget, omitted)
            tierJson.put(tier.jsonKey, packed.items)
            tierStats.put(
                JSONObject()
                    .put("tier", tier.jsonKey)
                    .put("budgetChars", budget)
                    .put("candidateCount", candidates.size)
                    .put("emittedCount", packed.emittedCount)
                    .put("omittedCount", packed.omittedCount)
                    .put("emittedChars", packed.emittedChars)
            )
        }

        val promptBudget = JSONObject()
            .put("strategy", "fixed_tier_budget")
            .put("targetContextTokens", targetContextTokens)
            .put("targetContextChars", targetChars)
            .put("tierWeights", JSONObject(tierWeights.mapKeys { it.key.jsonKey }))
            .put("originalChars", originalChars)
            .put("tiers", tierStats)
            .put("omittedPathCount", omitted.size)
            .put("omittedPaths", JSONArray(omitted.take(MAX_OMITTED_PATHS)))

        val context = JSONObject()
            .put("schemaVersion", 1)
            .put("promptBudget", promptBudget)
        PromptTier.entries.forEach { tier -> context.put(tier.jsonKey, tierJson.opt(tier.jsonKey)) }
        val emittedChars = context.jsonChars()
        promptBudget.put("emittedContextChars", emittedChars)
        promptBudget.put("estimatedContextTokens", estimateTokens(emittedChars))

        return BudgetedPromptContext(context, promptBudget)
    }

    private fun addCandidate(
        candidate: PromptCandidate,
        selectedByTier: Map<PromptTier, MutableList<PromptCandidate>>,
        seen: MutableSet<String>
    ) {
        if (candidate.dedupeKey in seen) return
        selectedByTier.getValue(candidate.tier).add(candidate)
        seen += candidate.dedupeKey
    }

    private fun packTier(
        tier: PromptTier,
        candidates: List<PromptCandidate>,
        budgetChars: Int,
        omitted: MutableList<String>
    ): PackedTier {
        val output = JSONArray()
        var emittedChars = 2
        var omittedCount = 0
        val sorted = if (tier == PromptTier.INTERACTION_KERNEL) {
            candidates
        } else {
            candidates.sortedWith(compareByDescending<PromptCandidate> { it.score }.thenBy { it.source }.thenBy { it.nodeId.orEmpty() }.thenBy { it.text.orEmpty() })
        }
        sorted.forEach { candidate ->
            val item = candidate.json
            val itemChars = item.jsonChars() + 1
            val alwaysInclude = tier == PromptTier.INTERACTION_KERNEL && output.length() == 0
            if (alwaysInclude || emittedChars + itemChars <= budgetChars) {
                output.put(item)
                emittedChars += itemChars
            } else {
                omittedCount += 1
                omitted += candidate.omittedPath
            }
        }
        return PackedTier(output, output.length(), omittedCount, emittedChars)
    }

    private fun buildInteractionKernel(
        goal: String,
        activeApp: ActiveApp?,
        deviceContext: DeviceContext?,
        uiState: PortalState?,
        history: List<String>,
        lastResults: JSONArray,
        doNotRepeat: JSONArray
    ): JSONObject {
        val extras = deviceContext?.extras
        val docsContext = extras?.optJSONObject("docsContext")
        val editor = extras?.optJSONObject("editor")
        val selectionContext = extras?.optJSONObject("selectionContext")
        val documentTextWindow = extras?.optJSONObject("documentTextWindow")
        val safety = extras?.optJSONObject("safety")
        val screenObservation = extras?.optJSONObject("screenObservation")
        return JSONObject()
            .put("goal", goal.cap(MAX_KERNEL_TEXT_CHARS))
            .put("activeApp", activeApp?.toPromptJson() ?: deviceContext?.activeApp?.toPromptJson() ?: JSONObject.NULL)
            .put(
                "surface",
                JSONObject()
                    .put("packageName", uiState?.packageName ?: activeApp?.packageName ?: deviceContext?.activeApp?.packageName ?: JSONObject.NULL)
                    .put("activityName", uiState?.activityName ?: activeApp?.activityName ?: deviceContext?.activeApp?.activityName ?: JSONObject.NULL)
                    .put("screenWidth", uiState?.screenWidth ?: JSONObject.NULL)
                    .put("screenHeight", uiState?.screenHeight ?: JSONObject.NULL)
            )
            .put("uiMode", docsContext?.optString("uiMode").takeIfNotBlank() ?: editor?.optString("uiMode").takeIfNotBlank() ?: JSONObject.NULL)
            .put("editor", editor?.compactJson(MAX_SUMMARY_TEXT_CHARS) ?: JSONObject.NULL)
            .put("selectionContext", selectionContext?.compactJson(MAX_SUMMARY_TEXT_CHARS) ?: JSONObject.NULL)
            .put("currentTextWindow", documentTextWindow?.currentWindowSummary() ?: JSONObject.NULL)
            .put("safety", safety?.compactJson(MAX_SUMMARY_TEXT_CHARS) ?: JSONObject.NULL)
            .put("screenState", screenObservation?.screenStateSummary() ?: JSONObject.NULL)
            .put("history", JSONArray(history.takeLast(6).map { it.cap(MAX_SUMMARY_TEXT_CHARS) }))
            .put("lastToolResults", lastResults.compactArray(MAX_SUMMARY_TEXT_CHARS, maxItems = 8))
            .put("doNotRepeat", doNotRepeat.compactArray(MAX_SUMMARY_TEXT_CHARS, maxItems = 12))
    }

    private fun collectPortalCandidates(state: PortalState?, goalTokens: Set<String>): List<PromptCandidate> {
        if (state == null) return emptyList()
        val nodes = state.nodes
        val adjacentIds = adjacentNodeIds(nodes)
        val candidates = mutableListOf<PromptCandidate>()
        nodes.forEachIndexed { index, node ->
            val label = node.label().cap(MAX_TEXT_CHARS).takeIfNotBlank()
            val goalScore = relevanceScore(label, goalTokens)
            val baseScore = node.nodeScore(goalScore) - index
            val nodeJson = node.toPromptJson(index, label, goalScore)
            if (node.visible && node.isActionable()) {
                candidates += PromptCandidate(
                    tier = PromptTier.VISIBLE_ACTIONABLE,
                    source = "uiState.nodes",
                    role = node.roleName(),
                    text = label,
                    nodeId = node.nodeId,
                    score = baseScore + 300,
                    json = nodeJson
                )
            } else if (node.visible && goalScore > 0 && label != null) {
                candidates += PromptCandidate(
                    tier = PromptTier.GOAL_RELEVANT_VISIBLE,
                    source = "uiState.nodes",
                    role = node.roleName(),
                    text = label,
                    nodeId = node.nodeId,
                    score = baseScore + 220,
                    json = nodeJson
                )
            } else if (node.isNavigationElement()) {
                candidates += PromptCandidate(
                    tier = PromptTier.NAVIGATION_STRUCTURE,
                    source = "uiState.nodes",
                    role = node.roleName(),
                    text = label,
                    nodeId = node.nodeId,
                    score = baseScore + 180,
                    json = nodeJson
                )
            } else if (node.nodeId != null && node.nodeId in adjacentIds) {
                candidates += PromptCandidate(
                    tier = PromptTier.ADJACENT_CONTEXT,
                    source = "uiState.nodes",
                    role = node.roleName(),
                    text = label,
                    nodeId = node.nodeId,
                    score = baseScore + 100,
                    json = nodeJson
                )
            }
        }
        return candidates
    }

    private fun collectDeviceContextCandidates(deviceContext: DeviceContext?, goalTokens: Set<String>): List<PromptCandidate> {
        val extras = deviceContext?.extras ?: return emptyList()
        val candidates = mutableListOf<PromptCandidate>()
        extras.optJSONObject("artifactContext")?.let { artifact ->
            artifact.optJSONArray("navigationTargets")?.forEachObject { index, target ->
                val label = target.optString("label").cap(MAX_TEXT_CHARS).takeIfNotBlank()
                val goalScore = relevanceScore(label, goalTokens)
                candidates += PromptCandidate(
                    tier = if (goalScore > 0 && target.optBoolean("visible", false)) PromptTier.GOAL_RELEVANT_VISIBLE else PromptTier.NAVIGATION_STRUCTURE,
                    source = "artifactContext.navigationTargets",
                    role = target.optString("kind", "target"),
                    text = label,
                    nodeId = target.optString("nodeId").takeIfNotBlank(),
                    score = 900 + goalScore - index,
                    json = target.compactCandidateJson("artifactContext.navigationTargets", MAX_TEXT_CHARS)
                )
            }
            artifact.optJSONArray("availableTools")?.let { tools ->
                candidates += summaryCandidate(
                    source = "artifactContext.availableTools",
                    role = "artifact_tools",
                    score = 700,
                    json = JSONObject().put("source", "artifactContext.availableTools").put("tools", tools.copyCappedStrings(MAX_TEXT_CHARS))
                )
            }
        }
        extras.optJSONArray("structuredCollections")?.forEachObject { collectionIndex, collection ->
            val summary = JSONObject()
                .put("source", "structuredCollections")
                .put("index", collectionIndex)
                .put("collectionType", collection.optString("collectionType", collection.optString("type", "collection")))
                .put("selectedLabel", collection.optString("selectedLabel").cap(MAX_TEXT_CHARS).takeIfNotBlank() ?: JSONObject.NULL)
            collection.optJSONArray("items")?.forEachObject { itemIndex, item ->
                val label = item.optString("primaryLabel", item.optString("title", item.optString("label"))).cap(MAX_TEXT_CHARS).takeIfNotBlank()
                val goalScore = relevanceScore(label, goalTokens)
                candidates += PromptCandidate(
                    tier = if (goalScore > 0) PromptTier.GOAL_RELEVANT_VISIBLE else PromptTier.NAVIGATION_STRUCTURE,
                    source = "structuredCollections.items",
                    role = item.optString("role", "collection_item"),
                    text = label,
                    nodeId = item.optString("tapTargetNodeId", item.optString("nodeId")).takeIfNotBlank(),
                    score = 760 + goalScore - itemIndex,
                    json = item.compactCandidateJson("structuredCollections.items", MAX_TEXT_CHARS)
                        .put("collectionIndex", collectionIndex)
                )
            }
            candidates += PromptCandidate(
                tier = PromptTier.NAVIGATION_STRUCTURE,
                source = "structuredCollections",
                role = "collection_summary",
                text = summary.optString("collectionType"),
                nodeId = null,
                score = 550 - collectionIndex,
                json = summary
            )
        }
        extras.optJSONObject("docsContext")?.optJSONArray("visibleDocuments")?.forEachObject { index, doc ->
            val label = doc.optString("title", doc.optString("label")).cap(MAX_TEXT_CHARS).takeIfNotBlank()
            val goalScore = relevanceScore(label, goalTokens)
            candidates += PromptCandidate(
                tier = if (goalScore > 0) PromptTier.GOAL_RELEVANT_VISIBLE else PromptTier.VISIBLE_ACTIONABLE,
                source = "docsContext.visibleDocuments",
                role = "visible_document",
                text = label,
                nodeId = doc.optString("tapTargetNodeId", doc.optString("nodeId")).takeIfNotBlank(),
                score = 820 + goalScore - index,
                json = doc.compactCandidateJson("docsContext.visibleDocuments", MAX_TEXT_CHARS)
            )
        }
        extras.optJSONObject("screenObservation")?.let { observation ->
            observation.optJSONArray("semanticCandidates")?.forEachObject { index, candidate ->
                val label = candidate.optString("label").cap(MAX_TEXT_CHARS).takeIfNotBlank()
                val goalScore = relevanceScore(label, goalTokens)
                val visible = candidate.optBoolean("visible", false)
                val actions = candidate.optJSONArray("actions")
                val tier = when {
                    visible && actions != null && actions.length() > 0 -> PromptTier.VISIBLE_ACTIONABLE
                    visible && goalScore > 0 -> PromptTier.GOAL_RELEVANT_VISIBLE
                    candidate.optString("role").contains("scroll", ignoreCase = true) -> PromptTier.NAVIGATION_STRUCTURE
                    else -> PromptTier.ADJACENT_CONTEXT
                }
                candidates += PromptCandidate(
                    tier = tier,
                    source = "screenObservation.semanticCandidates",
                    role = candidate.optString("role", "semantic_candidate"),
                    text = label,
                    nodeId = candidate.optString("nodeRef").takeIfNotBlank(),
                    score = 650 + goalScore - index,
                    json = candidate.compactCandidateJson("screenObservation.semanticCandidates", MAX_TEXT_CHARS)
                )
            }
        }
        extras.optJSONObject("accessibilityContentContext")?.let { accessibility ->
            val contentWindow = accessibility.optJSONObject("contentWindow")
            contentWindow?.optJSONArray("lines")?.forEachObject { index, line ->
                val text = line.optString("text").cap(MAX_TEXT_CHARS).takeIfNotBlank()
                val goalScore = relevanceScore(text, goalTokens)
                if (goalScore > 0 || line.optBoolean("heading", false) || line.optBoolean("focused", false)) {
                    candidates += PromptCandidate(
                        tier = if (goalScore > 0 && line.optBoolean("visible", false)) PromptTier.GOAL_RELEVANT_VISIBLE else PromptTier.NAVIGATION_STRUCTURE,
                        source = "accessibilityContentContext.contentWindow.lines",
                        role = if (line.optBoolean("heading", false)) "heading" else "content_line",
                        text = text,
                        nodeId = line.optString("nodeId").takeIfNotBlank(),
                        score = 610 + goalScore - index,
                        json = JSONObject()
                            .put("source", "accessibilityContentContext.contentWindow.lines")
                            .put("index", line.opt("index"))
                            .put("text", text ?: JSONObject.NULL)
                            .put("nodeId", line.opt("nodeId"))
                            .put("visible", line.optBoolean("visible", false))
                            .put("focused", line.optBoolean("focused", false))
                            .put("heading", line.optBoolean("heading", false))
                    )
                }
            }
            candidates += summaryCandidate(
                source = "accessibilityContentContext",
                role = "retrieval_handle",
                score = 600,
                json = JSONObject()
                    .put("source", "accessibilityContentContext")
                    .put("retrievalTool", "SEARCH_ACCESSIBILITY_CONTENT")
                    .put("provenance", accessibility.optJSONObject("provenance")?.compactJson(MAX_SUMMARY_TEXT_CHARS) ?: JSONObject.NULL)
                    .put("contentWindow", JSONObject().put("fullText", accessibility.fullTextSummary()))
            )
        }
        extras.optJSONObject("documentTextWindow")?.let { window ->
            val current = window.optString("currentParagraph").cap(MAX_SUMMARY_TEXT_CHARS).takeIfNotBlank()
            val before = window.optString("textBeforeCursor").cap(MAX_SUMMARY_TEXT_CHARS).takeIfNotBlank()
            val after = window.optString("textAfterCursor").cap(MAX_SUMMARY_TEXT_CHARS).takeIfNotBlank()
            if (current != null || before != null || after != null) {
                candidates += PromptCandidate(
                    tier = PromptTier.ADJACENT_CONTEXT,
                    source = "documentTextWindow",
                    role = "cursor_adjacent_text",
                    text = current ?: before ?: after,
                    nodeId = null,
                    score = 700,
                    json = JSONObject()
                        .put("source", "documentTextWindow")
                        .put("currentParagraph", current ?: JSONObject.NULL)
                        .put("textBeforeCursor", before ?: JSONObject.NULL)
                        .put("textAfterCursor", after ?: JSONObject.NULL)
                )
            }
        }
        return candidates
    }

    private fun collectPackageCandidates(packages: List<AppPackage>, goalTokens: Set<String>): List<PromptCandidate> = packages
        .asSequence()
        .filter { it.launchable != false }
        .mapIndexed { index, app ->
            val label = app.label.cap(MAX_TEXT_CHARS)
            val packageName = app.packageName
            val goalScore = relevanceScore(listOfNotNull(label, packageName).joinToString(" "), goalTokens)
            PromptCandidate(
                tier = PromptTier.SUMMARY_REMAINDER,
                source = "installedPackages",
                role = "app_package",
                text = label ?: packageName,
                nodeId = packageName,
                score = goalScore + if (app.launchable == true) 200 else 0 - index,
                json = app.toPromptJson().put("source", "installedPackages")
            )
        }
        .toList()

    private fun collectSummaryCandidates(deviceContext: DeviceContext?, uiState: PortalState?, packages: List<AppPackage>): List<PromptCandidate> {
        val extras = deviceContext?.extras
        val availableTools = linkedSetOf<String>()
        extras?.optJSONObject("artifactContext")?.optJSONArray("availableTools")?.forEachString { availableTools += it }
        availableTools += "SEARCH_ACCESSIBILITY_CONTENT"
        val json = JSONObject()
            .put("source", "summaryRemainder")
            .put("uiNodeCount", uiState?.nodes?.size ?: 0)
            .put("installedPackageCount", packages.size)
            .put("extraKeys", JSONArray(extras?.keys()?.asSequence()?.toList().orEmpty()))
            .put("retrievalTools", JSONArray(availableTools.toList()))
            .put("notes", JSONArray().put("Long full-text fields are summarized; use retrieval/search tools for omitted content."))
        return listOf(summaryCandidate("summaryRemainder", "source_summary", 1_000, json))
    }

    private fun summaryCandidate(source: String, role: String, score: Int, json: JSONObject): PromptCandidate = PromptCandidate(
        tier = PromptTier.SUMMARY_REMAINDER,
        source = source,
        role = role,
        text = json.optString("source"),
        nodeId = null,
        score = score,
        json = json
    )

    private fun adjacentNodeIds(nodes: List<UiNode>): Set<String> {
        val byParent = nodes.groupBy { it.parentId }
        val visibleAnchors = nodes.filter { it.visible && (it.focused || it.selected || it.isActionable()) }
        val ids = linkedSetOf<String>()
        visibleAnchors.forEach { node ->
            node.parentId?.let { ids += it }
            node.nodeId?.let { nodeId ->
                nodes.filter { it.parentId == nodeId }.mapNotNullTo(ids) { it.nodeId }
            }
            byParent[node.parentId].orEmpty().forEach { sibling ->
                if (sibling.nodeId != null && abs(sibling.childIndex - node.childIndex) <= 1) ids += sibling.nodeId
            }
        }
        return ids
    }

    private fun UiNode.toPromptJson(index: Int, label: String?, goalScore: Int): JSONObject {
        val json = JSONObject()
            .put("source", "uiState.nodes")
            .put("index", index)
            .put("id", nodeId ?: JSONObject.NULL)
            .put("text", label ?: JSONObject.NULL)
            .put("role", roleName())
            .put("visible", visible)
            .put("enabled", enabled)
            .put("clickable", clickable)
            .put("editable", editable)
            .put("focused", focused)
            .put("selected", selected)
            .put("scrollable", scrollable)
            .put("goalScore", goalScore)
        bounds?.let { bounds ->
            json.put(
                "bounds",
                JSONObject()
                    .put("left", bounds.left)
                    .put("top", bounds.top)
                    .put("right", bounds.right)
                    .put("bottom", bounds.bottom)
            )
        }
        viewIdResourceName.takeIfNotBlank()?.let { json.put("viewId", it.cap(MAX_TEXT_CHARS)) }
        parentId.takeIfNotBlank()?.let { json.put("parentId", it) }
        nodeId.takeIfNotBlank()?.let { json.put("nodeId", it) }
        tapTargetNodeId()?.let { json.put("tapTargetNodeId", it) }
        val actions = (actions + availableActions.map { it.name } + effectiveActions.map { it.name }).distinct()
        if (actions.isNotEmpty()) json.put("actions", JSONArray(actions.take(20)))
        return json
    }

    private fun UiNode.nodeScore(goalScore: Int): Int = goalScore + listOf(
        120 to focused,
        100 to selected,
        95 to editable,
        90 to clickable,
        80 to availableActions.isNotEmpty(),
        70 to effectiveActions.isNotEmpty(),
        65 to scrollable,
        60 to heading,
        50 to visible,
        40 to focusable,
        30 to (collectionItemInfo?.heading == true),
        20 to (collectionInfo != null)
    ).sumOf { (score, applies) -> if (applies) score else 0 }

    private fun UiNode.roleName(): String = when {
        editable -> "editable"
        scrollable -> "scroll_container"
        heading || collectionItemInfo?.heading == true -> "heading"
        collectionInfo != null -> "collection"
        collectionItemInfo != null -> "collection_item"
        checkable -> "toggle"
        clickable -> "button"
        else -> "node"
    }

    private fun UiNode.isActionable(): Boolean = enabled && (clickable || editable || focusable || scrollable || actions.isNotEmpty() || availableActions.isNotEmpty() || effectiveActions.isNotEmpty())

    private fun UiNode.isNavigationElement(): Boolean {
        val label = listOfNotNull(text, contentDescription, hintText, viewIdResourceName, className).joinToString(" ").lowercase()
        return visible && (
            heading || collectionInfo != null || collectionItemInfo?.heading == true || scrollable ||
                label.contains("tab") || label.contains("menu") || label.contains("search") ||
                label.contains("navigation") || label.contains("drawer") || label.contains("toolbar")
            )
    }

    private fun UiNode.label(): String? {
        if (password) return null
        return listOfNotNull(text, contentDescription, hintText, stateDescription, paneTitle, tooltipText)
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
    }

    private fun UiNode.tapTargetNodeId(): String? {
        if (availableActions.any { it.droidLmAction == "TAP_NODE" }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == "TAP_NODE" }?.targetNodeId ?: nodeId.takeIf { clickable }
    }

    private fun ActiveApp.toPromptJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("activityName", activityName ?: JSONObject.NULL)
        .put("label", label ?: JSONObject.NULL)

    private fun AppPackage.toPromptJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label ?: JSONObject.NULL)
        .put("enabled", enabled ?: JSONObject.NULL)
        .put("launchable", launchable ?: JSONObject.NULL)

    private fun DeviceContext.toFullJson(): JSONObject {
        val json = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("activeApp", activeApp?.toPromptJson() ?: JSONObject.NULL)
            .put("packageCount", packages.size)
        extras.keys().forEach { key -> json.put(key, extras.opt(key)) }
        return json
    }

    private fun PortalState.toCompactSourceJson(): JSONObject = JSONObject()
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("activityName", activityName ?: JSONObject.NULL)
        .put("screenWidth", screenWidth ?: JSONObject.NULL)
        .put("screenHeight", screenHeight ?: JSONObject.NULL)
        .put("nodeCount", nodes.size)

    private fun JSONObject.currentWindowSummary(): JSONObject {
        val summary = JSONObject()
        optString("visibleText").takeIfNotBlank()?.let { text ->
            if (text.length <= MAX_SUMMARY_TEXT_CHARS) summary.put("visibleText", text) else summary.put("visibleTextChars", text.length)
        }
        optString("currentParagraph").takeIfNotBlank()?.let { summary.put("currentParagraph", it.cap(MAX_SUMMARY_TEXT_CHARS)) }
        optString("textBeforeCursor").takeIfNotBlank()?.let { summary.put("textBeforeCursor", it.cap(MAX_SUMMARY_TEXT_CHARS)) }
        optString("textAfterCursor").takeIfNotBlank()?.let { summary.put("textAfterCursor", it.cap(MAX_SUMMARY_TEXT_CHARS)) }
        optString("focusedEditableText").takeIfNotBlank()?.let { text ->
            if (text.length <= MAX_SUMMARY_TEXT_CHARS) summary.put("focusedEditableText", text)
            summary.put("focusedEditableTextChars", text.length)
        }
        return summary
    }

    private fun JSONObject.screenStateSummary(): JSONObject = JSONObject()
        .put("observationId", optString("observationId").takeIfNotBlank() ?: JSONObject.NULL)
        .put("screenHash", optString("screenHash").takeIfNotBlank() ?: JSONObject.NULL)
        .put("keyboardVisible", optBoolean("keyboardVisible", false))
        .put("dialogVisible", optBoolean("dialogVisible", false))
        .put("loadingLikely", optBoolean("loadingLikely", false))
        .put("freshness", optString("freshness").takeIfNotBlank() ?: JSONObject.NULL)
        .put("provenance", optJSONObject("provenance")?.compactJson(MAX_SUMMARY_TEXT_CHARS) ?: JSONObject.NULL)

    private fun JSONObject.fullTextSummary(): JSONObject {
        val fullText = optJSONObject("contentWindow")?.optString("fullText").orEmpty()
        val summary = JSONObject()
            .put("omitted", fullText.isNotBlank())
            .put("chars", fullText.length)
        if (fullText.length in 1..MAX_SUMMARY_TEXT_CHARS) summary.put("preview", fullText)
        return summary
    }

    private fun JSONObject.compactCandidateJson(source: String, maxTextChars: Int): JSONObject {
        val json = JSONObject().put("source", source)
        listOf("label", "title", "primaryLabel", "kind", "role", "nodeId", "tapTargetNodeId", "labelNodeId", "sourceField").forEach { key ->
            val value = optString(key).takeIfNotBlank()
            if (value != null) json.put(key, value.cap(maxTextChars))
        }
        if (has("visible")) json.put("visible", optBoolean("visible"))
        if (has("confidence")) json.put("confidence", optDouble("confidence"))
        optJSONArray("actions")?.let { json.put("actions", it.copyCappedStrings(maxTextChars)) }
        if (has("selectionStart")) json.put("selectionStart", opt("selectionStart"))
        if (has("selectionEnd")) json.put("selectionEnd", opt("selectionEnd"))
        optJSONObject("bounds")?.let { json.put("bounds", it.compactJson(maxTextChars)) }
        return json
    }

    private fun JSONObject.compactJson(maxStringChars: Int): JSONObject {
        val output = JSONObject()
        keys().forEach { key ->
            output.put(key, compactValue(opt(key), maxStringChars))
        }
        return output
    }

    private fun JSONArray.compactArray(maxStringChars: Int, maxItems: Int): JSONArray {
        val output = JSONArray()
        for (index in 0 until length().coerceAtMost(maxItems)) {
            output.put(compactValue(opt(index), maxStringChars))
        }
        return output
    }

    private fun compactValue(value: Any?, maxStringChars: Int): Any? = when (value) {
        is JSONObject -> value.compactJson(maxStringChars)
        is JSONArray -> value.compactArray(maxStringChars, maxItems = 40)
        is String -> value.cap(maxStringChars)
        else -> value ?: JSONObject.NULL
    }

    private fun JSONArray.copyCappedStrings(maxStringChars: Int): JSONArray {
        val output = JSONArray()
        for (index in 0 until length()) {
            val value = opt(index)
            output.put(if (value is String) value.cap(maxStringChars) else value)
        }
        return output
    }

    private fun JSONArray.forEachObject(block: (Int, JSONObject) -> Unit) {
        for (index in 0 until length()) optJSONObject(index)?.let { block(index, it) }
    }

    private fun JSONArray.forEachString(block: (String) -> Unit) {
        for (index in 0 until length()) optString(index).takeIfNotBlank()?.let(block)
    }

    private fun goalTokens(goal: String): Set<String> = goal
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private fun relevanceScore(value: String?, goalTokens: Set<String>): Int {
        if (value.isNullOrBlank() || goalTokens.isEmpty()) return 0
        val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), " ")
        val matches = goalTokens.count { normalized.contains(it) }
        return matches * 90 + if (matches == goalTokens.size && matches > 0) 120 else 0
    }

    private fun estimateTokens(chars: Int): Int = (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    private fun JSONObject.jsonChars(): Int = toString().length
    private fun JSONArray.jsonChars(): Int = toString().length
    private fun String?.takeIfNotBlank(): String? = this?.takeIf { it.isNotBlank() }
    private fun String?.cap(maxChars: Int): String? = this?.let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    data class BudgetedPromptContext(val json: JSONObject, val budgetMetadata: JSONObject)

    private data class PackedTier(
        val items: JSONArray,
        val emittedCount: Int,
        val omittedCount: Int,
        val emittedChars: Int
    )

    private data class PromptCandidate(
        val tier: PromptTier,
        val source: String,
        val role: String,
        val text: String?,
        val nodeId: String?,
        val score: Int,
        val json: JSONObject
    ) {
        val dedupeKey: String = listOf(tier.jsonKey, nodeId.orEmpty(), normalizeText(text), source).joinToString("|")
        val omittedPath: String = listOf(source, role, nodeId ?: normalizeText(text)).joinToString(":")
    }

    enum class PromptTier(val jsonKey: String) {
        INTERACTION_KERNEL("interactionKernel"),
        VISIBLE_ACTIONABLE("visibleActionable"),
        GOAL_RELEVANT_VISIBLE("goalRelevantVisible"),
        NAVIGATION_STRUCTURE("navigationStructure"),
        ADJACENT_CONTEXT("adjacentContext"),
        SUMMARY_REMAINDER("summaryRemainder")
    }

    private fun normalizeText(value: String?): String = value
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()
        .orEmpty()

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "open", "show", "find", "search", "navigate", "scroll", "please", "droid", "droidlm"
    )
}
