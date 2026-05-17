/*
 * Copyright (c) 2026, PocketMS contributors
 */

package auth

import kotlinx.browser.document
import kotlinx.browser.window
import mono.remote.RemoteClient
import mono.remote.RemoteError
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

/**
 * Stands between the browser load event and [MonoSketchApplication] startup.
 * If the [RemoteClient] already has a stored session, the continuation runs
 * immediately. Otherwise a full-screen login form is mounted and the
 * continuation is invoked once authentication succeeds.
 */
class AuthGate(private val client: RemoteClient) {

    fun start(onAuthed: () -> Unit) {
        if (client.isAuthed) {
            mountLogoutButton()
            onAuthed()
        } else {
            renderLoginForm { mountLogoutButton(); onAuthed() }
        }
    }

    private fun renderLoginForm(onSuccess: () -> Unit) {
        val container = document.createElement("div") as HTMLDivElement
        container.id = "auth-gate"
        container.className =
            "fixed inset-0 z-50 flex items-center justify-center bg-[var(--workspace-bg-color)]"
        container.innerHTML = """
            <form id="auth-gate-form" class="w-80 p-6 rounded-md shadow-lg bg-[var(--shapetool-bg-color)] border border-[var(--shapetool-main-divider-color)]" autocomplete="off">
              <h1 class="text-lg font-semibold mb-4 text-[var(--shapetool-label-color)]">Sign in</h1>
              <label class="block text-xs mb-1 text-[var(--shapetool-label-color)]" for="auth-gate-email">Email</label>
              <input id="auth-gate-email" name="email" type="email" autocomplete="username" required
                     class="w-full mb-3 px-2 py-1 rounded bg-[var(--workspace-bg-color)] border border-[var(--shapetool-main-divider-color)] text-[var(--shapetool-label-color)] focus:outline-none focus:border-blue-500" />
              <label class="block text-xs mb-1 text-[var(--shapetool-label-color)]" for="auth-gate-password">Password</label>
              <input id="auth-gate-password" name="password" type="password" autocomplete="current-password" required
                     class="w-full mb-4 px-2 py-1 rounded bg-[var(--workspace-bg-color)] border border-[var(--shapetool-main-divider-color)] text-[var(--shapetool-label-color)] focus:outline-none focus:border-blue-500" />
              <button id="auth-gate-submit" type="submit"
                      class="w-full py-2 rounded bg-blue-600 text-white text-sm hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed">Sign in</button>
              <p id="auth-gate-error" class="mt-3 text-xs text-red-400 hidden"></p>
            </form>
        """.trimIndent()
        document.body?.appendChild(container)

        val form = document.getElementById("auth-gate-form") as HTMLFormElement
        val emailInput = document.getElementById("auth-gate-email") as HTMLInputElement
        val passwordInput = document.getElementById("auth-gate-password") as HTMLInputElement
        val submit = document.getElementById("auth-gate-submit") as HTMLButtonElement
        val errorEl = document.getElementById("auth-gate-error") as HTMLDivElement

        emailInput.focus()

        form.onsubmit = handler@{ event ->
            event.preventDefault()
            errorEl.classList.add("hidden")
            submit.disabled = true
            submit.textContent = "Signing in..."

            client.login(emailInput.value, passwordInput.value)
                .then {
                    container.remove()
                    onSuccess()
                }
                .catch { err ->
                    submit.disabled = false
                    submit.textContent = "Sign in"
                    errorEl.textContent = when (err) {
                        is RemoteError.Unauthenticated -> "Invalid email or password."
                        is RemoteError.Network -> "Network error. Check the server is reachable."
                        is RemoteError.Other -> "Sign-in failed (HTTP ${err.status})."
                        else -> "Sign-in failed."
                    }
                    errorEl.classList.remove("hidden")
                }
            null
        }
    }

    private fun mountLogoutButton() {
        val nav = document.getElementById("main-nav") ?: return
        if (document.getElementById("auth-gate-logout") != null) return

        val btn = document.createElement("button") as HTMLButtonElement
        btn.id = "auth-gate-logout"
        btn.type = "button"
        btn.textContent = "Sign out"
        btn.className =
            "absolute right-3 top-1/2 -translate-y-1/2 text-xs px-2 py-1 rounded " +
                "border border-[var(--shapetool-main-divider-color)] " +
                "text-[var(--shapetool-label-color)] hover:bg-[var(--shapetool-bg-color)]"
        btn.title = client.currentSession?.email ?: ""
        btn.onclick = {
            client.logout()
            window.location.reload()
        }
        nav.appendChild(btn)
    }
}
