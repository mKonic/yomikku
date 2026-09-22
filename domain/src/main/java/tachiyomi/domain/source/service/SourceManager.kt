package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.source.model.StubSource

interface SourceManager {

    val isInitialized: StateFlow<Boolean>

    val sources: Flow<List<Source>>

    suspend fun get(sourceKey: Long): Source?

    suspend fun getOrStub(sourceKey: Long): Source

    // KMK -->
    /**
     * Best-effort, non-suspending lookup for the few call sites that genuinely cannot suspend --
     * RecyclerView binds and preference fragments. Returns a stub if the extensions have not
     * finished loading, which is exactly what every caller used to get before [getOrStub] began
     * waiting for them. Prefer [getOrStub] everywhere else.
     */
    fun peek(sourceKey: Long): Source?

    fun peekOrStub(sourceKey: Long): Source

    fun peekVisibleOnlineSources(): List<HttpSource>

    fun peekVisibleSources(): List<Source>
    // KMK <--

    suspend fun getAll(): List<Source>

    suspend fun getOnlineSources(): List<HttpSource>

    // SY -->
    suspend fun getVisibleOnlineSources(): List<HttpSource>

    suspend fun getVisibleSources(): List<Source>
    // SY <--

    // KMK -->
    // KMK <--

    suspend fun getStubSources(): List<StubSource>
}
