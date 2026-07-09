package dev.pluginstudio.engine.models

sealed class Response<out T> {
    data class Success<T>(val data: T) : Response<T>()
    data class Error(val message: String, val exception: Throwable? = null) : Response<Nothing>()
}
