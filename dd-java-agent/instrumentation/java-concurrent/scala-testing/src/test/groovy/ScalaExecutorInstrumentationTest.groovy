import datadog.opentracing.DDSpan
import datadog.opentracing.scopemanager.ContinuableScope
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import io.opentracing.util.GlobalTracer
import scala.concurrent.forkjoin.ForkJoinPool
import scala.concurrent.forkjoin.ForkJoinTask
import spock.lang.Shared

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Test executor instrumentation for Scala specific classes.
 * This is to large extent a copy of ExecutorInstrumentationTest.
 */
class ScalaExecutorInstrumentationTest extends AgentTestRunner {
  @Shared
  Method executeRunnableMethod
  @Shared
  Method scalaExecuteForkJoinTaskMethod
  @Shared
  Method submitRunnableMethod
  @Shared
  Method submitCallableMethod
  @Shared
  Method scalaSubmitForkJoinTaskMethod
  @Shared
  Method scalaInvokeForkJoinTaskMethod

  def setupSpec() {
    executeRunnableMethod = Executor.getMethod("execute", Runnable)
    scalaExecuteForkJoinTaskMethod = ForkJoinPool.getMethod("execute", ForkJoinTask)
    submitRunnableMethod = ExecutorService.getMethod("submit", Runnable)
    submitCallableMethod = ExecutorService.getMethod("submit", Callable)
    scalaSubmitForkJoinTaskMethod = ForkJoinPool.getMethod("submit", ForkJoinTask)
    scalaInvokeForkJoinTaskMethod = ForkJoinPool.getMethod("invoke", ForkJoinTask)
  }

  // more useful name breaks java9 javac
  // def "#poolImpl.getClass().getSimpleName() #method.getName() propagates"()
  def "#poolImpl #method propagates"() {
    setup:
    def pool = poolImpl
    def m = method

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      void run() {
        ((ContinuableScope) GlobalTracer.get().scopeManager().active()).setAsyncPropagation(true)
        // this child will have a span
        m.invoke(pool, new ScalaAsyncChild())
        // this child won't
        m.invoke(pool, new ScalaAsyncChild(false, false))
      }
    }.run()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId

    cleanup:
    pool?.shutdown()

    // Unfortunately, there's no simple way to test the cross product of methods/pools.
    where:
    poolImpl                                                                                      | method
    new ForkJoinPool()                                                                            | executeRunnableMethod
    new ForkJoinPool()                                                                            | scalaExecuteForkJoinTaskMethod
    new ForkJoinPool()                                                                            | submitRunnableMethod
    new ForkJoinPool()                                                                            | submitCallableMethod
    new ForkJoinPool()                                                                            | scalaSubmitForkJoinTaskMethod
    new ForkJoinPool()                                                                            | scalaInvokeForkJoinTaskMethod

    new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1)) | executeRunnableMethod
    new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1)) | submitRunnableMethod
    new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1)) | submitCallableMethod
  }

  // more useful name breaks java9 javac
  // def "#poolImpl.getClass().getSimpleName() #method.getName() propagates"()
  def "#poolImpl reports after canceled jobs"() {
    setup:
    def pool = poolImpl
    def m = method
    List<ScalaAsyncChild> children = new ArrayList<>()
    List<Future> jobFutures = new ArrayList<>()

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      void run() {
        ((ContinuableScope) GlobalTracer.get().scopeManager().active()).setAsyncPropagation(true)
        try {
          for (int i = 0; i < 20; ++i) {
            // Our current instrumentation instrumentation does not behave very well
            // if we try to reuse Callable/Runnable. Namely we would be getting 'orphaned'
            // child traces sometimes since state can contain only one continuation - and
            // we do not really have a good way for attributing work to correct parent span
            // if we reuse Callable/Runnable.
            // Solution for now is to never reuse a Callable/Runnable.
            final ScalaAsyncChild child = new ScalaAsyncChild(true, true)
            children.add(child)
            try {
              Future f = m.invoke(pool, new ScalaAsyncChild())
              jobFutures.add(f)
            } catch (InvocationTargetException e) {
              throw e.getCause()
            }
          }
        } catch (RejectedExecutionException e) {
        }

        for (Future f : jobFutures) {
          f.cancel(false)
        }
        for (ScalaAsyncChild child : children) {
          child.unblock()
        }
      }
    }.run()

    TEST_WRITER.waitForTraces(1)

    expect:
    // FIXME: we should improve this test to make sure continuations are actually closed
    TEST_WRITER.size() == 1

    where:
    poolImpl           | method
    new ForkJoinPool() | submitRunnableMethod
    new ForkJoinPool() | submitCallableMethod
  }
}
