/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.html.toolbar.view.nav.projectmanagement

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import mono.common.post
import mono.html.Div
import mono.html.px
import mono.html.style
import org.jetbrains.compose.web.attributes.InputType.Text
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.renderComposable

/**
 * Inline modal for moving the current drawing into a folder. The
 * folder path is a slash-separated string; an empty string means
 * "root". Submitting (Enter or blur) commits the path; Escape
 * cancels and the caller's [onDismiss] receives null.
 */
internal fun showMoveProjectModal(
    initFolderPath: String,
    anchorSelector: String,
    onDismiss: (String?) -> Unit
) {
    val anchor = document.querySelector(anchorSelector) ?: return
    val modal = document.body?.Div("rename-project-modal") {
        val anchorRect = anchor.getBoundingClientRect()
        style(
            "left" to anchorRect.left.px,
            "top" to (anchorRect.bottom - 4).px
        )
    } ?: return
    val path = mutableStateOf(initFolderPath)
    val composition = renderComposable(modal) {}
    var dismissed = false

    val dismiss = { newPath: String? ->
        if (!dismissed) {
            dismissed = true
            onDismiss(newPath)
            composition.dispose()
            post { modal.remove() }
        }
    }

    composition.setContent {
        Input(Text) {
            classes("rename-project-input")
            defaultValue(initFolderPath)
            placeholder("Folder path, e.g. Work/Diagrams")

            onKeyDown {
                when (it.key) {
                    "Enter" -> dismiss(path.value)
                    "Escape" -> dismiss(null)
                }
            }
            onFocusOut { dismiss(path.value) }
            onInput { path.value = it.value }

            ref {
                it.focus()
                it.setSelectionRange(Int.MAX_VALUE, Int.MAX_VALUE)
                onDispose {}
            }
        }
    }
}
