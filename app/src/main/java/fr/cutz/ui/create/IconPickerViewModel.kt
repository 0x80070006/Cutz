package fr.cutz.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.cutz.data.icons.IconCatalog
import fr.cutz.data.icons.IconCategory
import fr.cutz.data.icons.IconIndex
import fr.cutz.data.icons.IconMeta
import fr.cutz.data.icons.IconPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class PickerFilter(val query: String = "", val category: IconCategory? = null, val packs: Set<IconPack> = emptySet())

data class PickerState(
    val loading: Boolean = true,
    val filter: PickerFilter = PickerFilter(),
    val results: List<IconMeta> = emptyList(),
    val recent: List<IconMeta> = emptyList(),
    val total: Int = 0,
) {
    /** "Récents" only makes sense on the unfiltered view. */
    val showRecent get() = recent.isNotEmpty() && filter == PickerFilter()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class IconPickerViewModel(
    private val loadIndex: suspend () -> IconIndex,
    recentIds: Flow<List<String>>,
) : ViewModel() {

    constructor(catalog: IconCatalog, recentIds: Flow<List<String>>) : this(catalog::index, recentIds)

    private val filter = MutableStateFlow(PickerFilter())
    private val index = flow { emit(loadIndex()) }

    val state: StateFlow<PickerState> = combine(index, filter.debounce(120), recentIds) { idx, f, recent -> Triple(idx, f, recent) }
        .mapLatest { (idx, f, recent) ->
            PickerState(
                loading = false,
                filter = f,
                results = idx.search(f.query, f.category, f.packs),
                recent = recent.mapNotNull { idx.get(it) },
                total = idx.icons.size,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerState())

    /** Immediate (non-debounced) filter, so the text field and chips never lag. */
    val currentFilter: StateFlow<PickerFilter> = filter

    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setCategory(c: IconCategory?) = filter.update { it.copy(category = c) }
    fun togglePack(p: IconPack) = filter.update { it.copy(packs = if (p in it.packs) it.packs - p else it.packs + p) }
    fun clear() = filter.update { PickerFilter() }
}
