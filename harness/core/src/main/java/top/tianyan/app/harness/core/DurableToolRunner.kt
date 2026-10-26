package top.tianyan.app.harness.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Intent commit -> execution -> result commit. Commit failures are infrastructure failures:
 * they must escape, never become an ordinary tool error or permit another model request.
 * Only execution failures may be converted to a model-facing error result.
 */
object DurableToolRunner {
    suspend fun <Result> run(
        commitIntent: suspend () -> Unit,
        execute: suspend () -> Result,
        commitResult: suspend (Result) -> Unit,
        executionFailure: (Throwable) -> Result,
    ): Result {
        currentCoroutineContext().ensureActive()
        commitIntent()
        currentCoroutineContext().ensureActive()
        val result = try {
            execute()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            executionFailure(failure)
        }
        currentCoroutineContext().ensureActive()
        commitResult(result)
        currentCoroutineContext().ensureActive()
        return result
    }
}
