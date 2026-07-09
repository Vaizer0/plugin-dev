package dev.pluginstudio.engine.models

data class PagedList<T>(
    val list: List<T>,
    val index: Int,
    val isLastPage: Boolean
)
