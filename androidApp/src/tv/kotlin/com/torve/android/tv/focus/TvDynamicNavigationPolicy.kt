package com.torve.android.tv.focus

/**
 * Keeps the rail's composition and item positions stable while optional
 * integrations hydrate. Availability changes only whether the reserved item is
 * focusable; it never inserts a node ahead of an existing focused item.
 */
internal fun availableNavigationRoutes(
    allRoutes: List<String>,
    optionalRoute: String,
    optionalRouteAvailable: Boolean,
): Set<String> = allRoutes
    .filterTo(linkedSetOf()) { route -> route != optionalRoute || optionalRouteAvailable }
