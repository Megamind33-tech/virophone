package com.viroreach.app.diagnostics

import android.os.SystemClock

/**
 * TEMPORARY diagnostics for the 0.4.62 composition crash (Stack.pop, Index -1):
 * where the UI was, and what had just changed, when a crash happened. The
 * crash report carries it.
 *
 * Only enum names and opaque ids are recorded — never message text, names,
 * phone numbers, tokens or codes.
 */
object Breadcrumbs {
    private const val KEEP = 25

    @Volatile private var startup = "?"
    @Volatile private var route = "?"
    @Volatile private var previousRoute = "?"
    @Volatile private var callState = "?"
    @Volatile private var subjectId: String? = null
    @Volatile private var routeChangedAt = 0L
    private val trail = ArrayDeque<String>()

    /** Root state: RestoringSession / Preparing are the splash, Unauthenticated / Authenticated the auth state. */
    fun startup(state: String) {
        if (state == startup) return
        startup = state
        add("startup=$state")
    }

    /** A primary-screen change. [subjectId] is a conversation or contact id, when the screen has one. */
    fun route(newRoute: String, subjectId: String?) {
        if (newRoute == route && subjectId == this.subjectId) return
        previousRoute = route
        route = newRoute
        this.subjectId = subjectId
        routeChangedAt = SystemClock.uptimeMillis()
        add("route=$newRoute${subjectId?.let { " id=$it" }.orEmpty()}")
    }

    fun call(state: String) {
        if (state == callState) return
        callState = state
        add("call=$state")
    }

    private fun add(event: String) = synchronized(trail) {
        trail.addLast("+${SystemClock.uptimeMillis()}ms $event")
        while (trail.size > KEEP) trail.removeFirst()
    }

    fun dump(): String {
        val sinceNav = SystemClock.uptimeMillis() - routeChangedAt
        return buildString {
            appendLine("Route: $route (previous: $previousRoute)")
            appendLine("Subject id: ${subjectId ?: "-"}")
            appendLine("Startup/auth: $startup · Call: $callState")
            appendLine(
                "Last navigation: ${if (routeChangedAt == 0L) "none" else "${sinceNav}ms before crash"}" +
                    if (routeChangedAt != 0L && sinceNav < 17) " (same frame)" else "",
            )
            appendLine("Recent:")
            synchronized(trail) { trail.forEach { appendLine("  $it") } }
        }
    }
}
