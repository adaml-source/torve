package com.torve.desktop.ui.v2.seeall

import com.torve.domain.model.MediaItem

/**
 * Describes a "See all" target. For paged API sections, use a known [sectionId]
 * (e.g. "TRENDING_MOVIES"); [fallbackItems] is ignored. For shelves without a
 * paged API (addons, custom, MDBList, etc.), use a "shelf:<key>" sectionId and
 * supply the snapshot in [fallbackItems] - the router populates
 * SeeAllViewModel.pendingItems so the page can load it.
 */
data class SeeAllRequest(
    val sectionId: String,
    val title: String,
    val fallbackItems: List<MediaItem> = emptyList(),
)
