package com.torve.android.launcher
import org.junit.Assert.*
import org.junit.Test
class PlayNextTargetTest {
    private val link = "https://torve.app/app/watch?owner=${launcherOwner("parent")}&type=tv&id=236235&season=2&episode=4"
    @Test fun launcherReturnsToTheExactEpisodeForTheActiveProfile() {
        assertEquals(PlayNextTarget("tv",236235,2,4),readPlayNextTarget(link,"parent")); assertNull(readPlayNextTarget(link,"child"))
    }
    @Test fun globalSearchOpensSeriesDetailsWithoutInventingAnEpisodeAndStillBindsToTheProfile() {
        val search = "https://torve.app/app/watch?owner=${launcherOwner("parent")}&type=tv&id=236235&origin=search"
        assertEquals(PlayNextTarget("tv", 236235, null, null), readPlayNextTarget(search, "parent"))
        assertNull(readPlayNextTarget(search, "child"))
        assertNull(readPlayNextTarget(search.replace("origin=search", "origin=unknown"), "parent"))
    }
    @Test fun malformedOrForeignLinksCannotChooseArbitraryPlayback() {
        for (invalid in listOf(link.replace("https:","http:"), link.replace("torve.app","other.test"), link.replace("episode=4","episode=0"),
            link+"&id=5",link.replace("id=236235","id=-5"),link.replace("&season=2",""))) assertNull(readPlayNextTarget(invalid,"parent"))
    }
}
