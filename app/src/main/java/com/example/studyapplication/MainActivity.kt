package com.example.studyapplication

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.findViewById<Button>(R.id.co_cancle_button_0).setOnClickListener{
            testShowManyError(CoroutineScope(Dispatchers.Main)) //演示，不正常取消协程，会打印很多错误的
        }
    }

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
            throw e
        }catch (e:Throwable){
            Result.failure(e)
        }
    }



    /**
     * @date 创建时间: 2023/8/29
     * @auther gaoxiaoxiong
     * @description 测试 JobCancellationException 如何产生的
     **/
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
}