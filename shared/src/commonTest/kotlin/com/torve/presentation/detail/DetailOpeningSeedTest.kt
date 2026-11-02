package com.torve.presentation.detail

import com.torve.domain.model.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlin.test.*

class DetailOpeningSeedTest {
    private val filter=ContentPolicyFilter()
    private val seed=MediaItem("tmdb:42",tmdbId=42,type=MediaType.MOVIE,title="Selected film")
    @Test fun lockedPolicyCannotDisplayAnAdultPreviewFromAnEarlierSelection() {
        assertNull(DetailOpeningSeed.accept("movie",42,seed.copy(adult=true),ContentPolicyState.lockedBootstrap(true),filter))
    }
    @Test fun placeholderAndStubMetadataCannotBecomeInteractivePreviews() {
        for (item in listOf(seed.copy(isStubDetail=true),seed.copy(isContentPlaceholder=true))) {
            assertNull(DetailOpeningSeed.accept("movie",42,item,ContentPolicyState.unrestricted(),filter))
        }
    }
    @Test fun calendarIdentityUsesItsSelectedParentWithoutReusingAnEpisodeNumber() {
        val show=seed.copy(id="trakt-calendar:42:3:8",type=MediaType.SERIES)
        assertEquals("42",DetailOpeningSeed.accept("tv",42,show,ContentPolicyState.unrestricted(),filter)?.id)
        assertNull(DetailOpeningSeed.accept("tv",8,show,ContentPolicyState.unrestricted(),filter))
    }
}
