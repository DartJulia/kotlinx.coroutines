/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

/**
 * Interface for code scopes that prevents scope code from accessing outer code scope methods (using outer code
 * scopes as implicit receivers).
 *
 * Example (on JVM):
 * ```
 * runBlocking {
 *     // receiver `this` is an [CoroutineScope]
 *
 *     runInterruptible {
 *         // receiver `this` is an [InterruptibleScope]
 *
 *         launch { /* ... */ } // <- Should not run in the scope of runBlocking. And won't compile.
 *         queue.take()
 *     }
 * }
 * ```
 */
@InternalCoroutinesApi
@CodeScopeDsl
public interface CodeScope

/**
 * DslMarker for CodeScope.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@DslMarker
internal annotation class CodeScopeDsl
