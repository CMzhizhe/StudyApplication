# StudyApplication
学习 JobCancellationException

#### JobCancellationException 如何产生的
案例demo
```
fun main() = runBlocking<Unit> {
        val job = launch {
            try {
                repeat(1000) { i ->
                    println("I'm sleeping $i ...")
                    delay(500L)
                }
            }catch (e:Exception){
                e.printStackTrace()
                //做日志上报操作
            } finally {
                println("I'm running finally")
            }
        }
        delay(1500L) // 延迟1.5秒
        println("main: I'm tired of waiting!")
        job.cancelAndJoin() // cancels the job and waits for its completion
        println("main: Now I can quit.")
    }

System.out: I'm sleeping 0 ...
I/System.out: I'm sleeping 1 ...
I/System.out: I'm sleeping 2 ...
I/System.out: main: I'm tired of waiting!
W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@de6ed5a
I/System.out: I'm running finally
I/System.out: main: Now I can quit.
```
可以看出在catch里面捕获到了异常，如果我们不加上这个try catch，会导致APP闪退么？
我从测试中发现不会导致闪退，当时我就在想，他们是如何处理的？跟踪源码我也没有找到具体的，但是我看到了他们是如何来取消的
#### 如何传递取消异常信息的？
```
  ...省去了很多方法
    private fun tryMakeCancelling(state: Incomplete, rootCause: Throwable): Boolean {
        // 看样子是通知取消的意思
        notifyCancelling(list, rootCause)
        return true
    }

   private fun notifyCancelling(list: NodeList, cause: Throwable) {
         //取消第一个child携程
        onCancelling(cause)
       //通过遍历的方式，去取消上下关联的携程，这个list里面的结构有点类似hashMap的链
        notifyHandlers<JobCancellingNode>(list, cause)
        //取消当前父携程
        cancelParent(cause) 
    }
```
针对onCancelling，我感觉有他里面有个注释，有必要说一下
```
 /**
Cause is an instance of CancellationException when the job was cancelled normally.
 It should not be treated as an error. In particular, it should not be reported to error logs.
导致正常取消时出现CancellationException实例。不应将其视为错误。特别是，不应将其报告给错误日志。
*/
    protected open fun onCancelling(cause: Throwable?) {}
```
看来他们底层有对 CancellationException 做了特殊的处理，**In particular, it should not be reported to error logs (特别是，不应将其报告给错误日志)** 所以我感觉以后遇到这样的CancellationException，可以不当错误来处理了吧（不自觉的感觉偷懒了想法）。
再看看 notifyHandlers
```
private inline fun <reified T: JobNode> notifyHandlers(list: NodeList, cause: Throwable?) {
        var exception: Throwable? = null
        list.forEach<T> { node ->
            try {
                node.invoke(cause)
            } catch (ex: Throwable) {
                exception?.apply { addSuppressedThrowable(ex) } ?: run {
                    exception =  CompletionHandlerException("Exception in completion handler $node for $this", ex)
                }
            }
        }
        exception?.let { handleOnCompletionException(it) }
    }
```
就是遍历，传递CancellationException

#### 那我应该如何优雅的处理类似这样的异常呢？
```
fun main() = runBlocking<Unit> {
        val job = launch {
            try {
                repeat(1000) { i ->
                    if(isActive){     //多加一个 isActive  即可
                        println("I'm sleeping $i ...")
                        delay(500L)
                    }
                }
            }catch (e:Exception){
                e.printStackTrace()
            }finally {
                println("I'm running finally")
            }
        }
        delay(1500L)
        println("main: I'm tired of waiting!")
        job.cancelAndJoin() 
        println("main: Now I can quit.")
    }
```

#### 那我用flow会出问题么？
```
 fun main3() = GlobalScope.launch{
        val startTime = System.currentTimeMillis()
        val job = launch(Dispatchers.Default) {
            flow<Int> {
                var nextPrintTime = startTime
                var i = 0
                while (i < 8) {
                    if (System.currentTimeMillis() >= nextPrintTime) {
                        i = i + 1
                        println("emit--->I'm sleeping ${i} ...")
                        emit(i)
                        nextPrintTime += 500
                    }
                }
            }.flowOn(Dispatchers.IO)
                .collect{
                    if (isActive)
                        println("emit--->value--->${it}")
                }
        }
        delay(1000L) // delay a bit
        println("main: I'm tired of waiting!")
        job.cancelAndJoin() // cancels the job and waits for its completion
        println("main: Now I can quit.")
    }
```
我测试了，没有问题，也不会闪退，为啥呢？主要是因为在emit发送的时候，也有捕获异常，然后再次抛出来，交由底层处理
```
override suspend fun emit(value: T) {
        return suspendCoroutineUninterceptedOrReturn sc@{ uCont ->
            try {
                emit(uCont, value)
            } catch (e: Throwable) { 
                lastEmissionContext = DownstreamExceptionContext(e, uCont.context)
                throw e  //再次抛出异常，最后还是交给底层去处理 CancellationException的异常
            }
        }
    }
```
#### 那麽我需要特别关注JobCancellationException么？
答案：不需要特别关注，大概知道，他是用来取消协程继续执行的，不过当你try cache了一个协程后，如果遇到了JobCancellationException建议再抛出来，为啥？如果不抛出来，你就**阻碍了协程的异常传播机制**，有点类似你独吞了这个异常，不告诉父容器
```
 fun testShowManyError(scope: CoroutineScope){
        val job = scope.launch {
            longRunningTask()
            println("coroutine is finished")
        }
        scope.launch {
            delay(5_000)
            println("cancelling")
            job.cancel()
        }
    }
    private suspend fun longRunningTask(){
        repeat(10){i->
            kotlin.runCatching {
                println("Executing net work call $i...")
                netWorkCall()
                println("netWorkCall---after")
            }.onFailure {
                it.printStackTrace()
            }
        }
    }
    private suspend fun netWorkCall()= delay(1_1000)

2023-08-29 00:00:30.761 com.example.studyapplication I/System.out: Executing net work call 0...
2023-08-29 00:00:32.858 com.example.studyapplication I/tudyapplicatio: ProcessProfilingInfo new_methods=1052 is saved saved_to_disk=1 resolve_classes_delay=8000
2023-08-29 00:00:35.766 com.example.studyapplication I/System.out: cancelling
2023-08-29 00:00:35.775 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.776 com.example.studyapplication I/System.out: Executing net work call 1...
2023-08-29 00:00:35.778 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.779 com.example.studyapplication I/System.out: Executing net work call 2...
2023-08-29 00:00:35.781 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.782 com.example.studyapplication I/System.out: Executing net work call 3...
2023-08-29 00:00:35.783 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.783 com.example.studyapplication I/System.out: Executing net work call 4...
2023-08-29 00:00:35.784 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.785 com.example.studyapplication I/System.out: Executing net work call 5...
2023-08-29 00:00:35.786 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.786 com.example.studyapplication I/System.out: Executing net work call 6...
2023-08-29 00:00:35.787 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.787 com.example.studyapplication I/System.out: Executing net work call 7...
2023-08-29 00:00:35.788 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.788 com.example.studyapplication I/System.out: Executing net work call 8...
2023-08-29 00:00:35.789 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.789 com.example.studyapplication I/System.out: Executing net work call 9...
2023-08-29 00:00:35.790 com.example.studyapplication W/System.err: kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@d927231
2023-08-29 00:00:35.790 com.example.studyapplication I/System.out: coroutine is finished
```
从上面的Log里面还可以观察到几个特点就是
1、netWorkCall---after 的Log没有打印
2、走完cancelling Log日志后，后面的Log都在 00:00:35 打印出来，也就是1秒之内，全部打印出来
从这2点说明，协程被取消后，协程后面的代码是不会执行的()，但是这种写法会导致协程前面的逻辑会执行

所以对于这样的异常捕获，最好是如下方案
```

/**
     * @date 创建时间: 2023/8/28
     * @auther gaoxiaoxiong
     * @description 解决会调用到很多的 JobCancellationException
     **/
    private suspend fun longRunningTaskV2(){
        repeat(10){i->
            runCatchingFixed {
                println("Executing net work call $i...")
                netWorkCall()
                println("netWorkCall---after")
            }.onFailure {
                it.printStackTrace()
            }
        }
    }
 inline fun <R> runCatchingFixed(block:()->R):Result<R>{
        return try {
            Result.success(block())
        }catch (e:CancellationException){
            throw e   //再次抛出异常
        }catch (e:Throwable){
            Result.failure(e)
        }
    }
```
