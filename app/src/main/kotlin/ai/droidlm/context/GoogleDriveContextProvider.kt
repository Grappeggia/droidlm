package ai.droidlm.context

import ai.droidlm.context.GoogleWorkspaceContextUtils.DRIVE_PACKAGE
import ai.droidlm.context.GoogleWorkspaceContextUtils.MAX_VISIBLE_TEXT
import ai.droidlm.context.GoogleWorkspaceContextUtils.cap
import ai.droidlm.context.GoogleWorkspaceContextUtils.hasAnyText
import ai.droidlm.context.GoogleWorkspaceContextUtils.inferTitle
import ai.droidlm.context.GoogleWorkspaceContextUtils.safetyContext
import ai.droidlm.context.GoogleWorkspaceContextUtils.visibleText
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

class GoogleDriveContextProvider : DeviceContextProvider {
    override suspend fun collect(request: DeviceContextRequest): JSONObject {
        val state = request.state ?: return JSONObject()
        if (state.packageName != DRIVE_PACKAGE) return JSONObject()

        val uiMode = detectUiMode(state)
        val contentExtraction = AccessibilityContentExtractor.extract(state, AccessibilityContentLimits.LONG_CONTEXT_MAX_CHARS)
        val visibleText = contentExtraction.fullText
        val visibleFileItems = visibleFileItems(state.nodes, state)
        val visibleFiles = JSONArray(visibleFileItems.map { StructuredCollectionContextBuilder.legacyVisibleEntity(it, "title") })
        val selectedFile = selectedFile(state.nodes, visibleFiles)
        val currentLocation = currentLocation(state.nodes)
        val searchContext = searchContext(state.nodes, uiMode)
        val actions = availableActions(uiMode, selectedFile.length() > 0)
        val artifactContext = ArtifactContextBuilder.build(
            source = "google_drive",
            type = "file_collection",
            title = currentLocation.optString("label").takeIf { it.isNotBlank() },
            uiMode = uiMode,
            state = state,
            visibleText = visibleText,
            focusedText = searchContext.optString("query"),
            currentBlock = selectedFile.optString("title"),
            availableActions = actions
        )
        val structuredCollections = StructuredCollectionContextBuilder.build(
            source = "google_drive",
            collectionType = "file_list",
            items = visibleFileItems,
            goal = request.goal,
            selectedLabel = selectedFile.optString("title").takeIf { it.isNotBlank() }
        )
        val safety = safetyContext(
            text = visibleText,
            sharingFlowActive = uiMode == "SHARE_DIALOG",
            deleteFlowActive = uiMode == "DELETE_DIALOG" || hasAnyText(state.nodes, "delete", "move to trash", "remove"),
            moveFlowActive = hasAnyText(state.nodes, "move to", "choose folder", "move here"),
            renameFlowActive = hasAnyText(state.nodes, "rename"),
            uploadFlowActive = uiMode == "UPLOAD_FLOW"
        )

        return JSONObject()
            .put(
                "driveContext",
                JSONObject()
                    .put("uiMode", uiMode)
                    .put("isGoogleDrive", true)
                    .put("currentLocation", currentLocation)
                    .put("visibleFiles", visibleFiles)
                    .put("selectedFile", selectedFile)
                    .put("searchContext", searchContext)
                    .put("visibleText", cap(visibleText, MAX_VISIBLE_TEXT))
                    .put("availableActions", JSONArray(actions))
            )
            .put("artifactContext", artifactContext)
            .put("structuredCollections", structuredCollections)
            .put("availableDriveActions", JSONArray(actions))
            .put("safety", safety)
    }

    private fun detectUiMode(state: PortalState): String = when {
        ArtifactContextBuilder.isShareDialog(state.nodes) -> "SHARE_DIALOG"
        hasAnyText(state.nodes, "delete", "move to trash", "remove forever") -> "DELETE_DIALOG"
        hasAnyText(state.nodes, "upload", "uploading", "upload from") -> "UPLOAD_FLOW"
        hasAnyText(state.nodes, "new folder", "google docs", "google sheets", "scan", "upload") -> "CREATE_MENU"
        hasAnyText(state.nodes, "search in drive", "search results", "no results") -> "SEARCH_RESULTS"
        hasAnyText(state.nodes, "open with", "download", "make available offline", "details") -> "FILE_PREVIEW"
        hasAnyText(state.nodes, "my drive", "shared with me", "recent", "starred", "offline", "trash") -> "FILE_LIST"
        else -> "UNKNOWN"
    }

    private fun currentLocation(nodes: List<UiNode>): JSONObject {
        val labels = listOf("My Drive", "Shared with me", "Recent", "Starred", "Offline", "Trash", "Computers")
        val text = visibleText(nodes)
        val location = labels.firstOrNull { text.contains(it, ignoreCase = true) }
            ?: inferTitle(nodes, genericLabels())
        return JSONObject()
            .put("label", location)
            .put("confidence", if (location != null) 0.6 else 0.0)
    }

    private fun visibleFileItems(
        nodes: List<UiNode>,
        state: PortalState
    ): List<StructuredCollectionContextBuilder.Item> = nodes.asSequence()
        .filter { it.visible && (it.clickable || it.effectiveActions.isNotEmpty() || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) }
        .mapIndexedNotNull { index, node -> fileItem(node, state, index) }
        .distinctBy { it.primaryLabel }
        .take(50)
        .toList()

    private fun fileItem(
        node: UiNode,
        state: PortalState,
        index: Int
    ): StructuredCollectionContextBuilder.Item? {
        val raw = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim()
        if (raw.isBlank() || raw.length < 2 || raw.length > 180) return null
        val lower = raw.lowercase()
        if (lower in genericLabels()) return null
        val type = when {
            lower.contains("folder") -> "folder"
            lower.contains("spreadsheet") || lower.endsWith(".csv") || lower.endsWith(".xlsx") -> "spreadsheet"
            lower.contains("document") || lower.endsWith(".docx") || lower.endsWith(".txt") -> "document"
            lower.endsWith(".pdf") -> "pdf"
            lower.endsWith(".jpg") || lower.endsWith(".png") -> "image"
            else -> JSONObject.NULL
        }
        val confidence = when {
            node.tapTargetNodeId() != null -> 0.72
            node.bounds != null -> 0.48
            else -> 0.3
        }
        return StructuredCollectionContextBuilder.itemFromNode(
            label = raw,
            entityType = "file",
            node = node,
            state = state,
            index = index,
            type = type,
            confidence = confidence
        )
    }

    private fun selectedFile(nodes: List<UiNode>, visibleFiles: JSONArray): JSONObject {
        val selected = nodes.firstOrNull { it.selected || it.focused }
        if (selected != null) {
            val selectedItem = fileItem(
                node = selected,
                state = PortalState(selected.packageName, null, null, null, nodes),
                index = 0
            )
            if (selectedItem != null) return StructuredCollectionContextBuilder.legacyVisibleEntity(selectedItem, "title")
        }
        return if (visibleFiles.length() == 1) visibleFiles.optJSONObject(0) ?: JSONObject() else JSONObject()
    }

    private fun searchContext(nodes: List<UiNode>, uiMode: String): JSONObject {
        val searchNode = nodes.firstOrNull {
            it.editable || listOfNotNull(it.text, it.contentDescription, it.viewIdResourceName)
                .any { value -> value.contains("search", ignoreCase = true) }
        }
        return JSONObject()
            .put("isSearchMode", uiMode == "SEARCH_RESULTS")
            .put("query", searchNode?.text)
            .put("searchNodeId", searchNode?.nodeId)
    }

    private fun availableActions(uiMode: String, hasSelectedFile: Boolean): List<String> {
        val actions = linkedSetOf("SEARCH_FILES", "OPEN_FILE", "OPEN_FOLDER")
        when (uiMode) {
            "FILE_LIST", "SEARCH_RESULTS", "FOLDER_VIEW" -> {
                actions += "CREATE_DOC"
                actions += "CREATE_SHEET"
                actions += "UPLOAD_FILE"
            }
            "FILE_PREVIEW" -> {
                actions += "DOWNLOAD"
                actions += "MAKE_AVAILABLE_OFFLINE"
                actions += "OPEN_WITH"
            }
            "CREATE_MENU" -> {
                actions += "CREATE_DOC"
                actions += "CREATE_SHEET"
                actions += "UPLOAD_FILE"
            }
            "SHARE_DIALOG" -> actions += "SHARE"
            "DELETE_DIALOG" -> actions += "DELETE"
        }
        if (hasSelectedFile) {
            actions += "RENAME"
            actions += "MOVE"
            actions += "STAR"
            actions += "SHARE"
            actions += "DELETE"
        }
        return actions.toList()
    }

    private fun UiNode.tapTargetNodeId(): String? {
        if (availableActions.any { it.droidLmAction == "TAP_NODE" }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == "TAP_NODE" }?.targetNodeId
            ?: nodeId.takeIf { clickable }
    }

    private fun genericLabels(): Set<String> = setOf(
        "drive", "google drive", "my drive", "shared with me", "recent", "starred", "offline", "trash",
        "search", "new", "home", "files", "folders", "notifications", "more options", "sort", "view"
    )
}
