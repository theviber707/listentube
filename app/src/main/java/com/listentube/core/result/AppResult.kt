// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.core.result

import kotlinx.coroutines.CancellationException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Discriminated union representing either a successful domain outcome [Success] containing [T],
 * or a typed domain failure [Failure] containing a [DomainError].
 */
sealed interface AppResult<out T> {

    data class Success<out T>(val data: T) : AppResult<T>

    data class Failure(val error: DomainError) : AppResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun errorOrNull(): DomainError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    companion object {
        fun <T> success(data: T): AppResult<T> = Success(data)
        fun failure(error: DomainError): AppResult<Nothing> = Failure(error)
    }
}

/**
 * Transforms the success value [T] into [R] using [transform], leaving [AppResult.Failure] untouched.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Failure -> this
    }
}

/**
 * Chains a dependent operation returning [AppResult] when this result is [AppResult.Success].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> transform(data)
        is AppResult.Failure -> this
    }
}

/**
 * Transforms the [DomainError] inside [AppResult.Failure] using [transform].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.mapError(transform: (DomainError) -> DomainError): AppResult<T> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> AppResult.Failure(transform(error))
    }
}

/**
 * Folds both branches of [AppResult] into a single value of type [R].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T, R> AppResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (DomainError) -> R,
): R {
    contract {
        callsInPlace(onSuccess, InvocationKind.AT_MOST_ONCE)
        callsInPlace(onFailure, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> onSuccess(data)
        is AppResult.Failure -> onFailure(error)
    }
}

/**
 * Executes side-effect [action] if this result is [AppResult.Success], returning the original [AppResult].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this is AppResult.Success) {
        action(data)
    }
    return this
}

/**
 * Executes side-effect [action] if this result is [AppResult.Failure], returning the original [AppResult].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.onFailure(action: (DomainError) -> Unit): AppResult<T> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this is AppResult.Failure) {
        action(error)
    }
    return this
}

/**
 * Returns the encapsulated value if [AppResult.Success] or computes a fallback from [defaultValue].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.getOrElse(defaultValue: (DomainError) -> T): T {
    contract {
        callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> defaultValue(error)
    }
}

/**
 * Returns the encapsulated value if [AppResult.Success] or [defaultValue] if [AppResult.Failure].
 */
fun <T> AppResult<T>.getOrDefault(defaultValue: T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Failure -> defaultValue
}

/**
 * Recovers from a [DomainError] by mapping it to a fallback value [T].
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.recover(transform: (DomainError) -> T): AppResult<T> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> AppResult.Success(transform(error))
    }
}

/**
 * Recovers from a [DomainError] by executing a fallback [AppResult]-producing computation.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> AppResult<T>.recoverWith(transform: (DomainError) -> AppResult<T>): AppResult<T> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> transform(error)
    }
}

/**
 * Executes [block] and wraps its output in [AppResult.Success], catching any non-cancellation
 * [Throwable] and converting it to [AppResult.Failure] via [errorMapper].
 *
 * Rethrows [CancellationException] immediately to preserve coroutine cancellation semantics.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> appRunCatching(
    errorMapper: (Throwable) -> DomainError = Throwable::toDomainError,
    block: () -> T,
): AppResult<T> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return try {
        AppResult.Success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AppResult.Failure(errorMapper(t))
    }
}
