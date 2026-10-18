package org.drynor.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import org.drynor.tools.rsinterface.FieldDifference
import org.drynor.tools.rsinterface.InterfaceAssets
import org.drynor.tools.rsinterface.InterfaceIssue
import org.drynor.tools.rsinterface.InterfaceStore
import org.drynor.tools.rsinterface.IssueSeverity

/**
 * Everything that is wrong with the open interface, in the order it will hurt.
 *
 * Two different questions get answered here and they are deliberately kept
 * apart:
 *
 * - **Validation** is structural and cheap. Out of range parents, cycles, a
 *   component parented to something that is not a layer, a layer with no size -
 *   the things that make an interface draw as nothing. It re-runs whenever the
 *   document changes, because it costs a pass over the component array.
 * - **Round-trip verification** encodes every component and decodes it straight
 *   back, and reports each field that came back different. That is the only
 *   honest answer to "will saving lose anything", and it is expensive enough to
 *   be a button rather than something that happens per frame.
 *
 * The round-trip stays a first-class button even though the if3 codec currently
 * round-trips the whole cache byte for byte, because that is a property of
 * today's encoder rather than of the format: a few constructs are still
 * type-gated (a colour on a component the encoder writes no colour block for,
 * `viewportZ` outside a `centreType` block) and an edit can walk into one of
 * them. Checking is cheap enough to do before a save and worth far more than
 * trusting that it is still true.
 */
class InterfaceValidationPanel(
    private val context: InterfaceEditContext,
    private val store: InterfaceStore,
    private val assets: InterfaceAssets,
) {
    var visible: Boolean = true

    private var issues: List<InterfaceIssue> = emptyList()
    private var issuesRevision = -1

    private var differences: List<FieldDifference> = emptyList()
    private var differencesRevision = -2
    private var verified = false

    private val filter = ImString(48)
    private var showInfo = false

    /** Counts for the window title and the status bar, refreshed with [issues]. */
    var errorCount: Int = 0
        private set
    var warningCount: Int = 0
        private set

    /** Recomputes the structural issues when the document has moved on. */
    fun refresh() {
        val document = context.document
        if (document == null) {
            issues = emptyList()
            errorCount = 0
            warningCount = 0
            issuesRevision = -1
            return
        }
        if (issuesRevision == context.revision) {
            return
        }
        issuesRevision = context.revision
        issues = document.validate(assets)
        errorCount = issues.count { it.severity == IssueSeverity.ERROR }
        warningCount = issues.count { it.severity == IssueSeverity.WARNING }
        if (differencesRevision != context.revision) {
            verified = false
        }
    }

    fun render() {
        if (!visible) {
            return
        }
        if (!ImGui.begin("Validation")) {
            ImGui.end()
            return
        }
        val document = context.document
        if (document == null) {
            ImGuiText.disabled("No interface open.")
            ImGui.end()
            return
        }
        refresh()

        summary()
        ImGui.separator()

        if (ImGui.collapsingHeader("Issues (${issues.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            issueList()
        }
        val reindex = document.lastReindexIssues
        if (reindex.isNotEmpty() && ImGui.collapsingHeader("Last edit lost references (${reindex.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            for (issue in reindex) {
                issueRow(issue)
            }
        }
        if (ImGui.collapsingHeader("Save fidelity")) {
            roundTrip()
        }
        ImGui.end()
    }

    private fun summary() {
        if (errorCount > 0) {
            coloured(1f, 0.45f, 0.4f, "$errorCount error(s)")
            ImGui.sameLine()
        }
        if (warningCount > 0) {
            coloured(1f, 0.78f, 0.35f, "$warningCount warning(s)")
            ImGui.sameLine()
        }
        if (errorCount == 0 && warningCount == 0) {
            coloured(0.4f, 0.9f, 0.55f, "No errors or warnings")
            ImGui.sameLine()
        }
        ImGuiText.disabled("${issues.size - errorCount - warningCount} note(s)")

        ImGui.setNextItemWidth(180f)
        ImGui.inputTextWithHint("##issuefilter", "code or message", filter)
        ImGui.sameLine()
        val info = ImBoolean(showInfo)
        if (ImGui.checkbox("notes", info)) {
            showInfo = info.get()
        }
        ImGui.sameLine()
        if (ImGui.smallButton("copy all")) {
            ImGui.setClipboardText(issues.joinToString("\n"))
        }
    }

    private fun issueList() {
        val needle = filter.get().trim().lowercase()
        var shown = 0
        // Errors first: a broken parent index makes every warning under it noise.
        for (severity in SEVERITY_ORDER) {
            for (issue in issues) {
                if (issue.severity != severity) {
                    continue
                }
                if (!showInfo && severity == IssueSeverity.INFO) {
                    continue
                }
                if (needle.isNotEmpty() &&
                    !issue.code.lowercase().contains(needle) &&
                    !issue.message.lowercase().contains(needle)
                ) {
                    continue
                }
                issueRow(issue)
                shown++
            }
        }
        if (shown == 0) {
            ImGuiText.disabled("Nothing to report.")
        }
    }

    private fun issueRow(issue: InterfaceIssue) {
        ImGui.pushID(issue.code + issue.componentIndex)
        if (issue.componentIndex >= 0) {
            if (ImGui.smallButton("${issue.componentIndex}")) {
                context.reveal(issue.componentIndex)
            }
        } else {
            ImGuiText.disabled(" -- ")
        }
        ImGui.sameLine()
        val colour = when (issue.severity) {
            IssueSeverity.ERROR -> floatArrayOf(1f, 0.45f, 0.4f)
            IssueSeverity.WARNING -> floatArrayOf(1f, 0.78f, 0.35f)
            IssueSeverity.INFO -> floatArrayOf(0.6f, 0.65f, 0.7f)
        }
        ImGui.pushStyleColor(ImGuiCol.Text, colour[0], colour[1], colour[2], 1f)
        ImGui.text(issue.code)
        ImGui.popStyleColor()
        ImGuiText.disabled(("    ${issue.message}"))
        ImGui.popID()
    }

    private fun roundTrip() {
        if (store.writable) {
            coloured(0.4f, 0.9f, 0.55f, "The cache is writable.")
        } else {
            coloured(1f, 0.78f, 0.35f, "Cache is read only")
            ImGuiText.wrapped((store.readOnlyReason ?: ""))
            ImGuiText.disabled(
                "Save writes the JSON sidecar instead. The encode itself is byte exact, so the " +
                    "sidecar and a cache write would agree - there is just nowhere to write to."
            )
        }
        ImGui.separator()
        if (ImGui.button("Check encode/decode round-trip")) {
            val document = context.document
            differences = if (document == null) emptyList() else store.verifyRoundTrip(document)
            differencesRevision = context.revision
            verified = true
        }
        if (!verified) {
            ImGuiText.disabled("Not checked for this revision.")
            return
        }
        val lossy = differences.filter { !it.derived }
        if (lossy.isEmpty()) {
            coloured(0.4f, 0.9f, 0.55f, "Every field survives a cache save.")
        } else {
            coloured(1f, 0.45f, 0.4f, "${lossy.size} field(s) would not survive a cache save")
        }
        if (!ImGui.beginChild("differences", 0f, DIFF_HEIGHT, ImGuiChildFlags.Borders)) {
            ImGui.endChild()
            return
        }
        for ((position, difference) in differences.withIndex()) {
            if (difference.derived) {
                continue
            }
            ImGui.pushID(position)
            if (ImGui.smallButton("${difference.componentIndex}")) {
                context.reveal(difference.componentIndex)
            }
            ImGui.popID()
            ImGui.sameLine()
            ImGui.text(difference.field)
            ImGuiText.disabled(("    wrote ${difference.written}"))
            ImGuiText.disabled(("    read  ${difference.decoded}"))
        }
        ImGui.endChild()
    }

    private fun coloured(red: Float, green: Float, blue: Float, text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, red, green, blue, 1f)
        ImGui.text(text)
        ImGui.popStyleColor()
    }

    companion object {
        private const val DIFF_HEIGHT = 200f

        private val SEVERITY_ORDER = listOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO)
    }
}
