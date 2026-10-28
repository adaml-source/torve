package com.torve.data.profile

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.*

private data class OwnedProfileState<T>(val owner: String, val value: T)

/** Synchronous .value reads and collectors both discard retained state on account, profile or child-rule changes. */
@OptIn(InternalCoroutinesApi::class)
class ProfilePolicyStateFlow<T>(private val family: FamilyControls, private val initial: T) : MutableStateFlow<T> {
    private val backing = MutableStateFlow(OwnedProfileState(family.contentSessionKey(), initial))
    override var value: T
        get() = backing.value.let { if (it.owner == family.contentSessionKey()) it.value else initial }
        set(value) { backing.value = OwnedProfileState(family.contentSessionKey(), value) }
    override val replayCache: List<T> get() = listOf(value)
    override val subscriptionCount: StateFlow<Int> get() = backing.subscriptionCount
    override fun compareAndSet(expect: T, update: T): Boolean {
        val previous = backing.value
        val owner = family.contentSessionKey()
        val visible = if (previous.owner == owner) previous.value else initial
        if (visible != expect) return false
        return backing.compareAndSet(previous, OwnedProfileState(owner, update))
    }
    override suspend fun emit(value: T) { this.value = value }
    override fun tryEmit(value: T): Boolean { this.value = value; return true }
    override fun resetReplayCache(): Unit = throw UnsupportedOperationException("StateFlow replay cannot be reset")
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        combine(backing, family.state) { _, _ -> value }.collect { collector.emit(value) }
        error("Profile state collection cannot complete")
    }
}
