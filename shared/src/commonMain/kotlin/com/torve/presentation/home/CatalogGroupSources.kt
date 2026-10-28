package com.torve.presentation.home

import com.torve.domain.model.CatalogGroupSource
import com.torve.domain.model.CustomSection

fun catalogGroupSources(state: HomeUiState, custom: List<CustomSection>): List<CatalogGroupSource> = buildList {
    state.shelves.forEach { add(CatalogGroupSource("catalog:${it.id}", it.title, it.items)) }
    state.addonShelves.forEach { add(CatalogGroupSource("addon:${it.id}", it.title, it.items)) }
    state.mdbListShelves.forEach { add(CatalogGroupSource("list:${it.id}", it.title, it.items)) }
    custom.forEach { add(CatalogGroupSource("custom:${it.id}", it.title, state.customShelves[it.id].orEmpty())) }
}.distinctBy { it.id }
