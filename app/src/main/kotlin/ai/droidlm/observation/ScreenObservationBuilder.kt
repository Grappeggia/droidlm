package ai.droidlm.observation

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import android.graphics.Rect
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

class ScreenObservationBuilder(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun build(
        state: PortalState,
        ocrBlocks: List<OcrBlock> = emptyList(),
        artifactContext: ArtifactContext? = null,
        previous: ScreenObservation? = null,
        freshness: ObservationFreshness = ObservationFreshness.UNKNOWN,
        ocrAttempted: Boolean = false,
        ocrError: String? = null
    ): ScreenObservation {
        val timestampMs = clock()
        val nodes = state.nodes.map { it.toObservedNode(state.packageName) }
        val windowTitle = state.nodes.firstNotNullOfOrNull { it.paneTitle?.takeIf(String::isNotBlank) }
        val keyboardVisible = nodes.any { it.focused && it.editable } || state.nodes.any { it.textEntryKey && it.visible }
        val dialogVisible = detectDialog(state.nodes)
        val loadingLikely = detectLoading(state.nodes)
        val screenHash = screenHash(state, windowTitle, keyboardVisible, dialogVisible, loadingLikely, nodes)
        val baseObservation = ScreenObservation(
            observationId = "obs-${timestampMs}-${screenHash.take(12)}-${UUID.randomUUID().toString().take(8)}",
            timestampMs = timestampMs,
            packageName = state.packageName,
            activityName = state.activityName,
            windowTitle = windowTitle,
            screenHash = screenHash,
            keyboardVisible = keyboardVisible,
            dialogVisible = dialogVisible,
            loadingLikely = loadingLikely,
            nodes = nodes,
            ocrBlocks = ocrBlocks,
            artifactContext = artifactContext,
            priorActionDelta = null,
            confidence = confidence(nodes, ocrBlocks, artifactContext, ocrAttempted, ocrError),
            freshness = freshness
        )
        return baseObservation.copy(priorActionDelta = previous?.let { deltaFrom(it, baseObservation) })
    }

    private fun UiNode.toObservedNode(defaultPackageName: String?): ObservedNode {
        val role = role()
        val actions = (availableActions.map { it.name } + effectiveActions.map { it.name } + actions)
            .map(NodeAction::from)
            .toSet()
        val fingerprint = stableFingerprint(defaultPackageName, role, actions)
        return ObservedNode(
            nodeRef = nodeId ?: fingerprint,
            stableFingerprint = fingerprint,
            text = if (password) null else text?.trimToNull(),
            contentDescription = contentDescription?.trimToNull(),
            className = className?.trimToNull(),
            role = role,
            bounds = bounds?.let { Rect(it) } ?: Rect(),
            visible = visible,
            enabled = enabled,
            clickable = clickable,
            focusable = focusable,
            focused = focused,
            editable = editable,
            scrollable = scrollable,
            checked = checked.takeIf { checkable },
            selected = selected,
            actions = actions
        )
    }

    private fun UiNode.stableFingerprint(defaultPackageName: String?, role: UiRole, actions: Set<NodeAction>): String {
        val label = normalizedLabel()
        val stableLabel = if (viewIdResourceName.isNullOrBlank()) label else ""
        val coarseBounds = bounds?.let { bounds ->
            listOf(
                (bounds.left / BOUNDS_BUCKET_PX) * BOUNDS_BUCKET_PX,
                (bounds.top / BOUNDS_BUCKET_PX) * BOUNDS_BUCKET_PX,
                (bounds.width() / BOUNDS_BUCKET_PX) * BOUNDS_BUCKET_PX,
                (bounds.height() / BOUNDS_BUCKET_PX) * BOUNDS_BUCKET_PX
            ).joinToString(",")
        }.orEmpty()
        val material = listOf(
            packageName ?: defaultPackageName.orEmpty(),
            viewIdResourceName.orEmpty(),
            className.orEmpty(),
            role.name,
            stableLabel,
            actions.map { it.name }.sorted().joinToString(","),
            depth.toString(),
            coarseBounds
        ).joinToString("|")
        return sha256(material).take(32)
    }

    private fun UiNode.role(): UiRole {
        val label = listOfNotNull(text, contentDescription, hintText, stateDescription, viewIdResourceName, className)
            .joinToString(" ")
            .lowercase()
        val klass = className.orEmpty().lowercase()
        return when {
            password -> UiRole.PASSWORD
            klass.contains("progress") || label.contains("progress") -> UiRole.PROGRESS
            label.contains("search") -> UiRole.SEARCH
            editable -> UiRole.EDITABLE
            rangeInfo != null || klass.contains("seekbar") || klass.contains("slider") -> UiRole.SLIDER
            checkable && klass.contains("switch") -> UiRole.TOGGLE
            checkable || klass.contains("checkbox") -> UiRole.CHECKBOX
            klass.contains("dialog") || paneTitle?.contains("dialog", ignoreCase = true) == true -> UiRole.DIALOG
            viewIdResourceName?.contains("tab", ignoreCase = true) == true || klass.contains("tab") -> UiRole.TAB
            collectionInfo != null || klass.contains("recyclerview") || klass.contains("listview") -> UiRole.LIST
            collectionItemInfo != null -> UiRole.LIST_ITEM
            scrollable -> UiRole.SCROLL_CONTAINER
            heading || collectionItemInfo?.heading == true -> UiRole.HEADING
            klass.contains("image") -> UiRole.IMAGE
            clickable || availableActions.any { it.droidLmAction == "TAP_NODE" } || effectiveActions.any { it.droidLmAction == "TAP_NODE" } -> UiRole.BUTTON
            !text.isNullOrBlank() || !contentDescription.isNullOrBlank() -> UiRole.TEXT
            else -> UiRole.NODE
        }
    }

    private fun UiNode.normalizedLabel(): String = listOfNotNull(
        if (password) null else text,
        contentDescription,
        hintText,
        stateDescription,
        paneTitle,
        tooltipText
    )
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
        .take(MAX_LABEL_FINGERPRINT_CHARS)

    private fun detectDialog(nodes: List<UiNode>): Boolean {
        val text = nodes.joinToString(" ") { node ->
            listOfNotNull(node.className, node.paneTitle, node.text, node.contentDescription).joinToString(" ")
        }.lowercase()
        return text.contains("dialog") || text.contains("alert") ||
            (text.contains("cancel") && (text.contains("ok") || text.contains("allow") || text.contains("deny")))
    }

    private fun detectLoading(nodes: List<UiNode>): Boolean {
        val text = nodes.joinToString(" ") { node ->
            listOfNotNull(node.className, node.text, node.contentDescription, node.stateDescription).joinToString(" ")
        }.lowercase()
        return text.contains("progressbar") || text.contains("loading") || text.contains("syncing") ||
            text.contains("please wait") || text.contains("refreshing")
    }

    private fun screenHash(
        state: PortalState,
        windowTitle: String?,
        keyboardVisible: Boolean,
        dialogVisible: Boolean,
        loadingLikely: Boolean,
        nodes: List<ObservedNode>
    ): String {
        val nodeMaterial = nodes
            .filter { it.visible }
            .take(MAX_HASH_NODES)
            .joinToString(";") { node ->
                listOf(
                    node.stableFingerprint,
                    node.role.name,
                    node.text.orEmpty().normalizedForHash(),
                    node.contentDescription.orEmpty().normalizedForHash(),
                    node.enabled.toString(),
                    node.checked?.toString().orEmpty(),
                    node.selected?.toString().orEmpty(),
                    node.actions.map { it.name }.sorted().joinToString(",")
                ).joinToString(":")
            }
        return sha256(
            listOf(
                state.packageName.orEmpty(),
                state.activityName.orEmpty(),
                windowTitle.orEmpty(),
                keyboardVisible.toString(),
                dialogVisible.toString(),
                loadingLikely.toString(),
                nodeMaterial
            ).joinToString("|")
        )
    }

    private fun confidence(
        nodes: List<ObservedNode>,
        ocrBlocks: List<OcrBlock>,
        artifactContext: ArtifactContext?,
        ocrAttempted: Boolean,
        ocrError: String?
    ): ObservationConfidence {
        val reasons = mutableListOf<String>()
        var score = 0.0
        if (nodes.isNotEmpty()) {
            score += 0.55
            reasons += "accessibility_nodes:${nodes.size}"
        } else {
            reasons += "accessibility_nodes_missing"
        }
        if (ocrBlocks.isNotEmpty()) {
            score += 0.25
            reasons += "ocr_blocks:${ocrBlocks.size}"
        } else if (ocrAttempted) {
            reasons += "ocr_empty"
        } else {
            reasons += "ocr_not_attempted"
        }
        artifactContext?.let {
            score += 0.12
            reasons += "artifact_context"
        }
        if (nodes.any { it.focused }) {
            score += 0.05
            reasons += "focused_node"
        }
        if (ocrError != null) {
            score -= 0.08
            reasons += "ocr_error:${ocrError.take(80)}"
        }
        return ObservationConfidence(score = score.coerceIn(0.0, 1.0).round(3), reasons = reasons)
    }

    private fun deltaFrom(previous: ScreenObservation, current: ScreenObservation): ActionDelta {
        val previousFingerprints = previous.nodes.map { it.stableFingerprint }.toSet()
        val currentFingerprints = current.nodes.map { it.stableFingerprint }.toSet()
        val previousTextByFingerprint = previous.nodes.associate { it.stableFingerprint to listOfNotNull(it.text, it.contentDescription).joinToString("|") }
        val changedTextCount = current.nodes.count { node ->
            previousTextByFingerprint[node.stableFingerprint]?.let { previousText ->
                previousText != listOfNotNull(node.text, node.contentDescription).joinToString("|")
            } == true
        }
        return ActionDelta(
            previousObservationId = previous.observationId,
            screenChanged = previous.screenHash != current.screenHash,
            packageChanged = previous.packageName != current.packageName || previous.activityName != current.activityName,
            addedNodeFingerprints = (currentFingerprints - previousFingerprints).take(MAX_DELTA_FINGERPRINTS),
            removedNodeFingerprints = (previousFingerprints - currentFingerprints).take(MAX_DELTA_FINGERPRINTS),
            changedTextNodeCount = changedTextCount,
            elapsedMs = (current.timestampMs - previous.timestampMs).coerceAtLeast(0)
        )
    }

    private fun String.normalizedForHash(): String = replace(Regex("\\s+"), " ").trim().lowercase().take(MAX_LABEL_FINGERPRINT_CHARS)

    private fun String.trimToNull(): String? = trim().takeIf { it.isNotBlank() }

    private fun Double.round(decimals: Int): Double {
        val scale = Math.pow(10.0, decimals.toDouble())
        return (this * scale).roundToInt() / scale
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BOUNDS_BUCKET_PX = 24
        const val MAX_HASH_NODES = 120
        const val MAX_LABEL_FINGERPRINT_CHARS = 240
        const val MAX_DELTA_FINGERPRINTS = 32
    }
}
