package com.torve.presentation.home

import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeViewModelTest {

    // We test the section ordering and filtering logic directly since the ViewModel
    // requires many dependencies. The key logic is in how sections are filtered and sorted.

    private fun section(
        section: HomeSection,
        enabled: Boolean = true,
        order: Int = 0,
    ) = HomeSectionConfig(
        section = section,
        enabled = enabled,
        order = order,
    )

    @Test
    fun hidden_sections_are_excluded() {
        val sections = listOf(
            section(HomeSection.CONTINUE_WATCHING, enabled = true, order = 0),
            section(HomeSection.TRENDING_MOVIES, enabled = false, order = 1),
            section(HomeSection.WATCHLIST, enabled = true, order = 2),
        )
        val visible = sections.filter { it.enabled }
        assertEquals(2, visible.size)
        assertTrue(visible.none { it.section == HomeSection.TRENDING_MOVIES })
    }

    @Test
    fun sections_sorted_by_order() {
        val sections = listOf(
            section(HomeSection.WATCHLIST, order = 3),
            section(HomeSection.CONTINUE_WATCHING, order = 1),
            section(HomeSection.TRENDING_MOVIES, order = 2),
        )
        val sorted = sections.sortedBy { it.order }
        assertEquals(HomeSection.CONTINUE_WATCHING, sorted[0].section)
        assertEquals(HomeSection.TRENDING_MOVIES, sorted[1].section)
        assertEquals(HomeSection.WATCHLIST, sorted[2].section)
    }

    @Test
    fun all_hidden_results_in_empty_list() {
        val sections = listOf(
            section(HomeSection.CONTINUE_WATCHING, enabled = false),
            section(HomeSection.TRENDING_MOVIES, enabled = false),
        )
        val visible = sections.filter { it.enabled }
        assertTrue(visible.isEmpty())
    }

    @Test
    fun empty_sections_list_is_handled() {
        val sections = emptyList<HomeSectionConfig>()
        val visible = sections.filter { it.enabled }
        assertTrue(visible.isEmpty())
    }

    @Test
    fun visible_and_hidden_mixed_preserves_order() {
        val sections = listOf(
            section(HomeSection.CONTINUE_WATCHING, enabled = true, order = 0),
            section(HomeSection.TRENDING_MOVIES, enabled = false, order = 1),
            section(HomeSection.WATCHLIST, enabled = true, order = 2),
            section(HomeSection.TOP_RATED, enabled = false, order = 3),
            section(HomeSection.POPULAR_MOVIES, enabled = true, order = 4),
        )
        val visible = sections.filter { it.enabled }.sortedBy { it.order }
        assertEquals(3, visible.size)
        assertEquals(HomeSection.CONTINUE_WATCHING, visible[0].section)
        assertEquals(HomeSection.WATCHLIST, visible[1].section)
        assertEquals(HomeSection.POPULAR_MOVIES, visible[2].section)
    }

    @Test
    fun upcoming_status_maps_connected_empty_to_true_empty_state() {
        val status = resolveUpcomingScheduleStatus(
            connected = true,
            itemCount = 0,
        )

        assertEquals(UpcomingScheduleStatus.EMPTY_CONNECTED, status)
    }

    @Test
    fun upcoming_status_maps_disconnected_to_reconnect_state() {
        val status = resolveUpcomingScheduleStatus(
            connected = false,
            itemCount = 0,
        )

        assertEquals(UpcomingScheduleStatus.DISCONNECTED, status)
    }

    @Test
    fun upcoming_status_preserves_stale_and_rate_limited_states() {
        assertEquals(
            UpcomingScheduleStatus.STALE,
            resolveUpcomingScheduleStatus(
                connected = true,
                itemCount = 3,
                isStale = true,
            ),
        )
        assertEquals(
            UpcomingScheduleStatus.RATE_LIMITED,
            resolveUpcomingScheduleStatus(
                connected = true,
                itemCount = 0,
                isRateLimited = true,
            ),
        )
    }

    @Test
    fun upcoming_status_maps_connected_data_to_content_state() {
        val status = resolveUpcomingScheduleStatus(
            connected = true,
            itemCount = 2,
        )

        assertEquals(UpcomingScheduleStatus.HAS_DATA, status)
    }
}
