package dev.shinsou.kmp.testing

/**
 * Marks the narrow test-access entries of a module: the public way for the tests of a higher module
 * to reach a declaration that is otherwise `internal` (a constructor, a failure seam, a counter).
 *
 * Each module that needs one keeps its entries in a single `<Module>TestAccess` object annotated
 * with this marker, next to the internal declarations it exposes. Using it is an error unless the
 * caller opts in with `@OptIn(TestOnlyApi::class)`, and only test source sets may do so:
 * `TestOnlyApiOptInTest` (composeApp desktopTest) fails when a product source set or a build script
 * opts in. Test code opts in per file with `@file:OptIn(TestOnlyApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Test-only access to an internal declaration; only test source sets may opt in",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class TestOnlyApi
