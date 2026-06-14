package com.torve.android.tv.focus

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.torve.android.tv.screens.TvSettingsCategory
import kotlin.math.abs

internal object TvSettingsItemIds {
    const val ACCOUNT_PAIR_DEVICE = "settings/account/pair_device"
    const val ACCOUNT_PAIRED_DEVICES = "settings/account/paired_devices"
    const val ACCOUNT_ACTIVATED_DEVICES = "settings/account/activated_devices"
    const val ACCOUNT_SYNC_STATUS = "settings/account/sync_status"
    const val ACCOUNT_SYNC_ERROR = "settings/account/sync_error"
    const val ACCOUNT_AUTH_ACCOUNT = "settings/account/auth_account"
    const val ACCOUNT_BETA_PROGRAM = "settings/account/beta_program"
    const val ACCOUNT_IMPORT_SETUP = "settings/account/import_setup"
    const val ACCOUNT_IMPORT_REFRESH = "settings/account/import_refresh"
    const val ACCOUNT_AUTH_PRIMARY_ACTION = "settings/account/auth_primary_action"
    const val ACCOUNT_AUTH_VERIFY = "settings/account/auth_verify"
    const val ACCOUNT_AUTH_EMAIL = "settings/account/auth_email"
    const val ACCOUNT_AUTH_PASSWORD = "settings/account/auth_password"
    const val ACCOUNT_AUTH_CONFIRM_PASSWORD = "settings/account/auth_confirm_password"
    const val ACCOUNT_AUTH_LOGOUT = "settings/account/auth_logout"
    const val ACCOUNT_AUTH_DELETE = "settings/account/auth_delete"
    const val ACCOUNT_AUTH_SUBMIT = "settings/account/auth_submit"
    const val ACCOUNT_AUTH_TOGGLE = "settings/account/auth_toggle"
    const val ACCOUNT_AUTH_FORGOT_PASSWORD = "settings/account/auth_forgot_password"
    const val ACCOUNT_AUTH_PAIR_WITH_PHONE = "settings/account/auth_pair_with_phone"
    const val ACCOUNT_SUBSCRIPTION_MANAGE_DEVICES = "settings/account/subscription_manage_devices"
    const val ACCOUNT_SUBSCRIPTION_MONTHLY = "settings/account/subscription_monthly"
    const val ACCOUNT_SUBSCRIPTION_LIFETIME = "settings/account/subscription_lifetime"
    const val ACCOUNT_SUBSCRIPTION_MANAGE_BILLING = "settings/account/subscription_manage_billing"
    const val ACCOUNT_SUBSCRIPTION_REFRESH = "settings/account/subscription_refresh"
    const val ACCOUNT_SUBSCRIPTION_RETRY = "settings/account/subscription_retry"
    const val ACCOUNT_SUBSCRIPTION_RESTORE = "settings/account/subscription_restore"
    const val PLAYBACK_MAX_QUALITY = "settings/playback/maximum_quality"
    const val PLAYBACK_MIN_QUALITY = "settings/playback/minimum_quality"
    const val PLAYBACK_AUTOPLAY = "settings/playback/autoplay"
    const val PLAYBACK_AUTOPLAY_NEXT = "settings/playback/autoplay_next"
    const val PLAYBACK_DEDUPE = "settings/playback/dedupe"
    const val PLAYBACK_AUDIO_MODE = "settings/playback/audio_mode"
    const val PLAYBACK_AUDIO_PASSTHROUGH = "settings/playback/audio_passthrough"
    const val PLAYBACK_AUDIO_SURROUND = "settings/playback/audio_surround"
    const val APPEARANCE_REDUCE_MOTION = "settings/appearance/reduce_motion"
    const val APPEARANCE_LANGUAGE = "settings/appearance/language"
    const val APPEARANCE_REGION = "settings/appearance/region"
    const val APPEARANCE_HOME_LAYOUT = "settings/appearance/home_layout"
    const val APPEARANCE_RATINGS = "settings/appearance/ratings"
    const val APPEARANCE_POSTER_TITLES = "settings/appearance/poster_titles"
    const val APPEARANCE_SEE_ALL_POSTER_COLUMNS = "settings/appearance/see_all_poster_columns"
    const val LIBRARY_CHANNELS = "settings/library/channels"
    const val LIBRARY_ADD_PLAYLIST = "settings/library/add_playlist"
    const val LIBRARY_REFRESH_EPG = "settings/library/refresh_epg"
    const val LIBRARY_MANAGE_CHANNELS = "settings/library/manage_channels"
    const val CONNECTIONS_PAIRING = "settings/connections/pairing"
    const val CONNECTIONS_TRAKT = "settings/connections/trakt"
    const val CONNECTIONS_TRAKT_RECONNECT = "settings/connections/trakt_reconnect"
    const val CONNECTIONS_TRAKT_DISCONNECT = "settings/connections/trakt_disconnect"
    const val CONNECTIONS_SIMKL = "settings/connections/simkl"
    const val CONNECTIONS_SIMKL_DISCONNECT = "settings/connections/simkl_disconnect"
    const val ADVANCED_ENTRY = "settings/advanced/entry"
    const val ADVANCED_PANDA = "settings/advanced/panda"
    const val ADVANCED_REAL_DEBRID = "settings/advanced/real_debrid"
    const val ADVANCED_PHONE_MDBLIST = "settings/advanced/phone_mdblist"
    const val ADVANCED_PHONE_JELLYFIN = "settings/advanced/phone_jellyfin"
    const val ADVANCED_PHONE_PLEX = "settings/advanced/phone_plex"
    const val ADVANCED_OMDB_KEY = "settings/advanced/omdb_key"
    const val ADVANCED_OMDB_TEST = "settings/advanced/omdb_test"
    const val ADVANCED_OPENSUBTITLES_KEY = "settings/advanced/opensubtitles_key"
    const val ADVANCED_MDBLIST_KEY = "settings/advanced/mdblist_key"
    const val ADVANCED_JELLYFIN_URL = "settings/advanced/jellyfin_url"
    const val ADVANCED_JELLYFIN_API_KEY = "settings/advanced/jellyfin_api_key"
    const val ADVANCED_JELLYFIN_TEST = "settings/advanced/jellyfin_test"
    const val ADVANCED_PLEX_URL = "settings/advanced/plex_url"
    const val ADVANCED_PLEX_TOKEN = "settings/advanced/plex_token"
    const val ADVANCED_PLEX_TEST = "settings/advanced/plex_test"
    const val ADVANCED_KODI_ADD = "settings/advanced/kodi_add"
    const val ADVANCED_AI_PROVIDER = "settings/advanced/ai_provider"
    const val ADVANCED_AI_API_KEY = "settings/advanced/ai_api_key"
    const val ADVANCED_AI_TEST = "settings/advanced/ai_test"
    const val ADVANCED_DIAGNOSTICS = "settings/advanced/diagnostics"
    const val ABOUT_VERSION = "settings/about/version"
    const val ABOUT_BUILD = "settings/about/build"
    const val ABOUT_STATS = "settings/about/stats"
    const val ABOUT_SUPPORT = "settings/about/support"
    const val ABOUT_REPORT_ISSUE = "settings/about/report_issue"
    const val ABOUT_TERMS = "settings/about/terms"
    const val ABOUT_LEGAL = "settings/about/legal"
}

internal data class TvSettingsFocusTarget(
    val itemId: String,
    val category: TvSettingsCategory,
    val listIndex: Int,
    val focusTargetType: String,
)

internal data class TvSettingsFocusOrigin(
    val itemId: String,
    val category: TvSettingsCategory,
    val listIndex: Int,
    val focusTargetType: String,
    val listSnapshot: TvFocusListSnapshot?,
    val requestedAtMillis: Long,
    val restoreToken: Long,
    val reason: String,
)

internal class TvSettingsFocusStateMachine(
    private val selectedCategoryState: MutableState<TvSettingsCategory>,
    private val focusedItemIdState: MutableState<String?>,
    private val pendingRestoreState: MutableState<TvSettingsFocusOrigin?>,
    private val savedReturnTargetState: MutableState<TvSettingsFocusOrigin?>,
    private val pendingFocusRepairState: MutableState<TvSettingsFocusOrigin?>,
) {
    constructor(
        initialCategory: TvSettingsCategory = TvSettingsCategory.ACCOUNT,
    ) : this(
        selectedCategoryState = mutableStateOf(initialCategory),
        focusedItemIdState = mutableStateOf(null),
        pendingRestoreState = mutableStateOf(null),
        savedReturnTargetState = mutableStateOf(null),
        pendingFocusRepairState = mutableStateOf(null),
    )

    private val logTag = "TvSettingsFocus"
    private val requesterByItemId = mutableMapOf<String, FocusRequester>()
    private val targetByItemId = mutableMapOf<String, TvSettingsFocusTarget>()
    private val registrationsByItemId = mutableStateMapOf<String, Int>()
    private val defaultItemByCategory = mutableStateMapOf<TvSettingsCategory, String>()
    private val lastFocusedItemByCategory = mutableStateMapOf<TvSettingsCategory, String>()
    private val fallbackRequesterByCategory = mutableStateMapOf<TvSettingsCategory, FocusRequester>()
    private var nextRestoreToken by mutableLongStateOf(0L)
    private var registrationVersion by mutableLongStateOf(0L)

    var selectedCategory by selectedCategoryState

    var focusedItemId by focusedItemIdState
        private set

    var pendingRestore by pendingRestoreState
        private set

    var savedReturnTarget by savedReturnTargetState
        private set

    var pendingFocusRepair by pendingFocusRepairState
        private set

    val currentRegistrationVersion: Long
        get() = registrationVersion

    fun registerItem(
        target: TvSettingsFocusTarget,
        requester: FocusRequester,
        isDefaultEntry: Boolean = false,
    ): FocusRequester {
        requesterByItemId[target.itemId] = requester
        targetByItemId[target.itemId] = target
        registrationsByItemId[target.itemId] = (registrationsByItemId[target.itemId] ?: 0) + 1
        if (isDefaultEntry) {
            defaultItemByCategory[target.category] = target.itemId
        }
        registrationVersion += 1L
        return requester
    }

    fun syncRegisteredItem(
        target: TvSettingsFocusTarget,
        requester: FocusRequester,
        isDefaultEntry: Boolean = false,
    ) {
        val requesterChanged = requesterByItemId[target.itemId] !== requester
        val targetChanged = targetByItemId[target.itemId] != target
        val defaultChanged = isDefaultEntry && defaultItemByCategory[target.category] != target.itemId
        requesterByItemId[target.itemId] = requester
        targetByItemId[target.itemId] = target
        if (isDefaultEntry) {
            defaultItemByCategory[target.category] = target.itemId
        }
        if (requesterChanged || targetChanged || defaultChanged) {
            registrationVersion += 1L
        }
    }

    fun unregisterItem(itemId: String) {
        val count = registrationsByItemId[itemId] ?: return
        if (count <= 1) {
            val target = targetByItemId[itemId]
            if (target != null && focusedItemId == itemId && target.category == selectedCategory) {
                pendingFocusRepair = TvSettingsFocusOrigin(
                    itemId = itemId,
                    category = target.category,
                    listIndex = target.listIndex,
                    focusTargetType = target.focusTargetType,
                    listSnapshot = null,
                    requestedAtMillis = System.currentTimeMillis(),
                    restoreToken = nextToken(),
                    reason = "mutation_invalidation",
                )
                debugLog(
                    "focus_invalidated screen=settings category=${target.category.name} " +
                        "item=$itemId rowIndex=${target.listIndex} type=${target.focusTargetType} " +
                        "token=${pendingFocusRepair?.restoreToken ?: -1L}",
                )
            } else if (target != null && focusedItemId == itemId) {
                debugLog(
                    "focus_invalidated_ignored screen=settings category=${target.category.name} " +
                        "item=$itemId rowIndex=${target.listIndex} type=${target.focusTargetType} " +
                        "selectedCategory=${selectedCategory.name}",
                )
            }
            registrationsByItemId.remove(itemId)
            targetByItemId.remove(itemId)
            requesterByItemId.remove(itemId)
            if (focusedItemId == itemId) {
                focusedItemId = null
            }
            lastFocusedItemByCategory.entries.removeAll { it.value == itemId }
            defaultItemByCategory.entries.removeAll { it.value == itemId }
        } else {
            registrationsByItemId[itemId] = count - 1
        }
        registrationVersion += 1L
    }

    fun setDefaultEntry(category: TvSettingsCategory, itemId: String) {
        defaultItemByCategory[category] = itemId
    }

    fun markFocused(
        itemId: String,
        fallbackRequester: FocusRequester? = requesterByItemId[itemId],
    ) {
        val target = targetByItemId[itemId] ?: return
        selectedCategory = target.category
        focusedItemId = itemId
        lastFocusedItemByCategory[target.category] = itemId
        fallbackRequester?.let { fallbackRequesterByCategory[target.category] = it }
        if (savedReturnTarget?.itemId == itemId) {
            savedReturnTarget = null
        }
        if (pendingFocusRepair?.itemId == itemId) {
            pendingFocusRepair = null
        }
        debugLog(
            "focus_gained screen=settings category=${target.category.name} " +
                "item=$itemId rowIndex=${target.listIndex} type=${target.focusTargetType} " +
                "token=${pendingRestore?.restoreToken ?: -1L}",
        )
    }

    fun rememberFocusedRequester(
        category: TvSettingsCategory,
        requester: FocusRequester,
        itemId: String? = null,
    ) {
        fallbackRequesterByCategory[category] = requester
        itemId?.let { knownItemId ->
            val target = targetByItemId[knownItemId] ?: return
            lastFocusedItemByCategory[target.category] = knownItemId
        }
    }

    fun captureOrigin(
        itemId: String,
        outerListState: LazyListState? = null,
        reason: String = "launch",
        requestedAtMillis: Long = System.currentTimeMillis(),
    ): TvSettingsFocusOrigin? {
        val target = targetByItemId[itemId] ?: return null
        selectedCategory = target.category
        val origin = TvSettingsFocusOrigin(
            itemId = itemId,
            category = target.category,
            listIndex = target.listIndex,
            focusTargetType = target.focusTargetType,
            listSnapshot = outerListState?.toFocusSnapshot(),
            requestedAtMillis = requestedAtMillis,
            restoreToken = nextToken(),
            reason = reason,
        )
        pendingRestore = origin
        debugLog(
            "origin_captured screen=settings category=${origin.category.name} " +
                "item=${origin.itemId} rowIndex=${origin.listIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return origin
    }

    fun requestRestore(
        itemId: String? = pendingRestore?.itemId
            ?: savedReturnTarget?.itemId
            ?: focusedItemId
            ?: lastFocusedItemByCategory[selectedCategory],
        reason: String,
        outerListState: LazyListState? = null,
    ): TvSettingsFocusOrigin? {
        val targetId = itemId ?: return null
        val target = targetByItemId[targetId] ?: return null
        val origin = TvSettingsFocusOrigin(
            itemId = targetId,
            category = target.category,
            listIndex = target.listIndex,
            focusTargetType = target.focusTargetType,
            listSnapshot = outerListState?.toFocusSnapshot() ?: pendingRestore?.listSnapshot,
            requestedAtMillis = System.currentTimeMillis(),
            restoreToken = nextToken(),
            reason = reason,
        )
        pendingRestore = origin
        selectedCategory = target.category
        debugLog(
            "restore_requested screen=settings category=${origin.category.name} " +
                "item=${origin.itemId} rowIndex=${origin.listIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return origin
    }

    fun saveReturnTarget(
        itemId: String? = focusedItemId ?: lastFocusedItemByCategory[selectedCategory],
        reason: String,
        outerListState: LazyListState? = null,
    ): TvSettingsFocusOrigin? {
        val targetId = itemId ?: return null
        val target = targetByItemId[targetId] ?: return null
        val origin = TvSettingsFocusOrigin(
            itemId = targetId,
            category = target.category,
            listIndex = target.listIndex,
            focusTargetType = target.focusTargetType,
            listSnapshot = outerListState?.toFocusSnapshot() ?: savedReturnTarget?.listSnapshot,
            requestedAtMillis = System.currentTimeMillis(),
            restoreToken = nextToken(),
            reason = reason,
        )
        savedReturnTarget = origin
        selectedCategory = target.category
        debugLog(
            "return_target_saved screen=settings category=${origin.category.name} " +
                "item=${origin.itemId} rowIndex=${origin.listIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return origin
    }

    fun clearPendingRestore() {
        pendingRestore = null
    }

    fun clearSavedReturnTarget() {
        savedReturnTarget = null
    }

    fun clearPendingFocusRepair() {
        pendingFocusRepair = null
    }

    fun hasPendingRestore(): Boolean = pendingRestore != null

    fun entryRequesterForCurrentState(): FocusRequester? {
        pendingRestore?.let { restore ->
            requesterByItemId[restore.itemId]?.let { return it }
        }
        savedReturnTarget?.let { restore ->
            requesterByItemId[restore.itemId]?.let { return it }
        }
        focusedItemId?.let { itemId ->
            requesterByItemId[itemId]?.let { return it }
        }
        lastFocusedItemByCategory[selectedCategory]?.let { itemId ->
            requesterByItemId[itemId]?.let { return it }
        }
        fallbackRequesterByCategory[selectedCategory]?.let { fallbackRequester ->
            if (requesterByItemId.values.any { it === fallbackRequester }) {
                return fallbackRequester
            }
        }
        defaultItemByCategory[selectedCategory]?.let { itemId ->
            requesterByItemId[itemId]?.let { return it }
        }
        return null
    }

    fun entryItemIdForCurrentState(): String? {
        pendingRestore?.let { restore ->
            if (requesterByItemId.containsKey(restore.itemId)) {
                return restore.itemId
            }
        }
        savedReturnTarget?.let { restore ->
            if (requesterByItemId.containsKey(restore.itemId)) {
                return restore.itemId
            }
        }
        focusedItemId?.let { itemId ->
            if (requesterByItemId.containsKey(itemId)) {
                return itemId
            }
        }
        lastFocusedItemByCategory[selectedCategory]?.let { itemId ->
            if (requesterByItemId.containsKey(itemId)) {
                return itemId
            }
        }
        defaultItemByCategory[selectedCategory]?.let { itemId ->
            if (requesterByItemId.containsKey(itemId)) {
                return itemId
            }
        }
        return null
    }

    fun isItemRegistered(itemId: String): Boolean {
        return requesterByItemId.containsKey(itemId)
    }

    fun requesterForItemId(itemId: String): FocusRequester? = requesterByItemId[itemId]

    fun targetForItemId(itemId: String): TvSettingsFocusTarget? = targetByItemId[itemId]

    fun defaultItemIdForCategory(category: TvSettingsCategory): String? = defaultItemByCategory[category]

    fun visibleTargetsInCategory(category: TvSettingsCategory): List<TvSettingsFocusTarget> {
        return registrationsByItemId.keys
            .mapNotNull { targetByItemId[it] }
            .filter { it.category == category }
            .distinctBy { it.itemId }
            .sortedWith(compareBy<TvSettingsFocusTarget> { it.listIndex }.thenBy { it.itemId })
    }

    fun visibleItemIdsInCategory(category: TvSettingsCategory): List<String> {
        return visibleTargetsInCategory(category).map { it.itemId }
    }

    fun resolveMutationRepairCandidates(origin: TvSettingsFocusOrigin): List<TvSettingsFocusTarget> {
        val visibleInCategory = visibleTargetsInCategory(origin.category)
        if (visibleInCategory.isEmpty()) {
            return emptyList()
        }

        val exact = visibleInCategory.firstOrNull { it.itemId == origin.itemId }
        val nextOrSame = visibleInCategory.firstOrNull { it.listIndex >= origin.listIndex }
        val previous = visibleInCategory.lastOrNull { it.listIndex < origin.listIndex }
        val firstVisible = visibleInCategory.firstOrNull()

        return listOfNotNull(exact, nextOrSame, previous, firstVisible).distinctBy { it.itemId }
    }

    suspend fun restorePendingFocus(
        outerListState: LazyListState? = null,
        isScreenActive: () -> Boolean = { true },
        maxAttempts: Int = 24,
    ): Boolean {
        val origin = pendingRestore ?: return false
        if (!isScreenActive()) return false
        selectedCategory = origin.category
        debugLog(
            "restore_begin screen=settings category=${origin.category.name} " +
                "item=${origin.itemId} rowIndex=${origin.listIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )

        origin.listSnapshot?.let { snapshot ->
            outerListState?.scrollToItem(snapshot.firstVisibleItemIndex, snapshot.firstVisibleItemScrollOffset)
        }

        repeat(maxAttempts) {
            withFrameNanos { }
            if (!isScreenActive()) return false

            val candidates = resolveCandidates(origin)
            for (candidate in candidates) {
                val requester = requesterByItemId[candidate.itemId] ?: continue
                debugLog(
                    "restore_candidate screen=settings category=${candidate.category.name} " +
                        "item=${candidate.itemId} rowIndex=${candidate.listIndex} type=${candidate.focusTargetType} " +
                        "token=${origin.restoreToken} reason=${origin.reason}",
                )
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                if (focusedItemId == candidate.itemId) {
                    debugLog(
                        "restore_success screen=settings category=${candidate.category.name} " +
                            "item=${candidate.itemId} rowIndex=${candidate.listIndex} type=${candidate.focusTargetType} " +
                            "token=${origin.restoreToken} reason=${origin.reason}",
                    )
                    if (pendingRestore?.restoreToken == origin.restoreToken) {
                        pendingRestore = null
                    }
                    return true
                }
            }
        }

        debugLog(
            "restore_failed screen=settings category=${origin.category.name} " +
                "item=${origin.itemId} rowIndex=${origin.listIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return false
    }

    internal fun resolveCandidates(origin: TvSettingsFocusOrigin): List<TvSettingsFocusTarget> {
        val availableTargets = registrationsByItemId.keys
            .mapNotNull { targetByItemId[it] }
            .distinctBy { it.itemId }

        val exact = availableTargets.firstOrNull { it.itemId == origin.itemId }
        if (exact != null) {
            return listOf(exact)
        }

        val sameCategory = availableTargets
            .filter { it.category == origin.category }
            .sortedWith(
                compareBy<TvSettingsFocusTarget> { abs(it.listIndex - origin.listIndex) }
                    .thenBy { it.listIndex }
                    .thenBy { it.itemId },
            )
        if (sameCategory.isNotEmpty()) {
            return sameCategory
        }

        val defaultInCategory = defaultItemByCategory[origin.category]
            ?.let { defaultId -> availableTargets.firstOrNull { it.itemId == defaultId } }
        if (defaultInCategory != null) {
            return listOf(defaultInCategory)
        }

        return availableTargets
            .sortedWith(
                compareBy<TvSettingsFocusTarget> { abs(it.listIndex - origin.listIndex) }
                    .thenBy { it.listIndex }
                    .thenBy { it.itemId },
            )
    }

    private fun debugLog(message: String) {
        runCatching { Log.i(logTag, message) }
    }

    private fun nextToken(): Long {
        nextRestoreToken += 1L
        return nextRestoreToken
    }
}

@Composable
internal fun rememberTvSettingsFocusStateMachine(
    key: String,
    initialCategory: TvSettingsCategory = TvSettingsCategory.ACCOUNT,
): TvSettingsFocusStateMachine {
    val selectedCategoryState = rememberSaveable(
        key = "${key}_selected_category",
        stateSaver = Saver(
            save = { category: TvSettingsCategory -> category.name },
            restore = { saved -> TvSettingsCategory.valueOf(saved) },
        ),
    ) {
        mutableStateOf(initialCategory)
    }
    val focusedItemIdState = rememberSaveable(key = "${key}_focused_item") {
        mutableStateOf<String?>(null)
    }
    val pendingRestoreState = rememberSaveable(
        key = "${key}_pending_restore",
        stateSaver = tvSettingsFocusOriginSaver,
    ) {
        mutableStateOf<TvSettingsFocusOrigin?>(null)
    }
    val savedReturnTargetState = rememberSaveable(
        key = "${key}_saved_return_target",
        stateSaver = tvSettingsFocusOriginSaver,
    ) {
        mutableStateOf<TvSettingsFocusOrigin?>(null)
    }
    val pendingFocusRepairState = rememberSaveable(
        key = "${key}_pending_focus_repair",
        stateSaver = tvSettingsFocusOriginSaver,
    ) {
        mutableStateOf<TvSettingsFocusOrigin?>(null)
    }
    return remember(key) {
        TvSettingsFocusStateMachine(
            selectedCategoryState = selectedCategoryState,
            focusedItemIdState = focusedItemIdState,
            pendingRestoreState = pendingRestoreState,
            savedReturnTargetState = savedReturnTargetState,
            pendingFocusRepairState = pendingFocusRepairState,
        )
    }
}

@Composable
internal fun rememberRegisteredTvSettingsFocusRequester(
    controller: TvSettingsFocusStateMachine,
    target: TvSettingsFocusTarget,
    externalRequester: FocusRequester? = null,
    isDefaultEntry: Boolean = false,
): FocusRequester {
    val requester = externalRequester ?: remember(target.itemId) { FocusRequester() }
    SideEffect {
        controller.syncRegisteredItem(target = target, requester = requester, isDefaultEntry = isDefaultEntry)
    }
    DisposableEffect(controller, target.itemId, requester, isDefaultEntry) {
        controller.registerItem(target = target, requester = requester, isDefaultEntry = isDefaultEntry)
        onDispose {
            controller.unregisterItem(target.itemId)
        }
    }
    return requester
}

private fun LazyListState.toFocusSnapshot(): TvFocusListSnapshot {
    return TvFocusListSnapshot(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    )
}

private val tvSettingsFocusOriginSaver = Saver<TvSettingsFocusOrigin?, List<Any?>>(
    save = { origin ->
        origin?.let {
            listOf(
                it.itemId,
                it.category.name,
                it.listIndex,
                it.focusTargetType,
                it.listSnapshot?.firstVisibleItemIndex,
                it.listSnapshot?.firstVisibleItemScrollOffset,
                it.requestedAtMillis,
                it.restoreToken,
                it.reason,
            )
        }
    },
    restore = { saved ->
        if (saved.size < 9) {
            null
        } else {
            TvSettingsFocusOrigin(
                itemId = saved[0] as String,
                category = TvSettingsCategory.valueOf(saved[1] as String),
                listIndex = saved[2] as Int,
                focusTargetType = saved[3] as String,
                listSnapshot = if (saved[4] != null && saved[5] != null) {
                    TvFocusListSnapshot(
                        firstVisibleItemIndex = saved[4] as Int,
                        firstVisibleItemScrollOffset = saved[5] as Int,
                    )
                } else {
                    null
                },
                requestedAtMillis = saved[6] as Long,
                restoreToken = saved[7] as Long,
                reason = saved[8] as String,
            )
        }
    },
)
