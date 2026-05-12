package com.redclient.orbital.core

/**
 * Small sealed alternative to [kotlin.Result] that can carry a typed error
 * without exceptions. Used at the UI boundary where we want to show
 * actionable error messages (never stacktraces).
 */
sealed class OrbitalResult<out T> {

    data class Ok<T>(val value: T) : OrbitalResult<T>()

    data class Err(val message: String, val cause: Throwable? = null) :
        OrbitalResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    inline fun <R> map(transform: (T) -> R): OrbitalResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun onOk(block: (T) -> Unit): OrbitalResult<T> {
        if (this is Ok) block(value)
        return this
    }

    inline fun onErr(block: (Err) -> Unit): OrbitalResult<T> {
        if (this is Err) block(this)
        return this
    }

    companion object {
        inline fun <T> runCatching(message: String, block: () -> T): OrbitalResult<T> =
            try {
                Ok(block())
            } catch (t: Throwable) {
                Err(message, t)
            }
    }
}
