package com.lin.hippyagent.core.pool

import com.lin.hippyagent.core.model.ToolCallInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ObjectPool<T>(
    private val factory: () -> T,
    private val reset: (T) -> Unit,
    private val maxSize: Int = 16
) {
    private val pool = ConcurrentLinkedQueue<T>()
    private val poolSize = AtomicInteger(0)

    fun acquire(): T {
        val obj = pool.poll()
        return if (obj != null) {
            poolSize.decrementAndGet()
            obj
        } else {
            factory()
        }
    }

    fun release(obj: T) {
        if (poolSize.get() < maxSize) {
            reset(obj)
            pool.offer(obj)
            poolSize.incrementAndGet()
        }
    }

    inline fun <R> use(block: (T) -> R): R {
        val obj = acquire()
        try {
            return block(obj)
        } finally {
            release(obj)
        }
    }
}

class StringBuilderPool(maxSize: Int = 8) {
    private val pool = ConcurrentLinkedQueue<StringBuilder>()
    private val poolSize = AtomicInteger(0)
    private val maxSize = maxSize

    fun acquire(): StringBuilder {
        val sb = pool.poll()
        return if (sb != null) {
            poolSize.decrementAndGet()
            sb
        } else {
            StringBuilder(256)
        }
    }

    fun release(sb: StringBuilder) {
        if (poolSize.get() < maxSize) {
            sb.clear()
            pool.offer(sb)
            poolSize.incrementAndGet()
        }
    }

    inline fun <R> use(block: (StringBuilder) -> R): R {
        val sb = acquire()
        try {
            return block(sb)
        } finally {
            release(sb)
        }
    }

    fun acquireAndBuild(content: String): String {
        val sb = acquire()
        try {
            sb.append(content)
            return sb.toString()
        } finally {
            release(sb)
        }
    }
}

class FloatArrayPool(
    private val arraySize: Int,
    maxSize: Int = 8
) {
    private val pool = ConcurrentLinkedQueue<FloatArray>()
    private val poolSize = AtomicInteger(0)
    private val maxSize = maxSize

    fun acquire(): FloatArray {
        val arr = pool.poll()
        return if (arr != null) {
            poolSize.decrementAndGet()
            arr
        } else {
            FloatArray(arraySize)
        }
    }

    fun release(arr: FloatArray) {
        if (arr.size == arraySize && poolSize.get() < maxSize) {
            arr.fill(0f)
            pool.offer(arr)
            poolSize.incrementAndGet()
        }
    }

    inline fun <R> use(block: (FloatArray) -> R): R {
        val arr = acquire()
        try {
            return block(arr)
        } finally {
            release(arr)
        }
    }
}

class ByteArrayOutputStreamPool(
    private val initialBufferSize: Int = 64 * 1024,
    maxSize: Int = 4
) {
    private val pool = ConcurrentLinkedQueue<ByteArrayOutputStream>()
    private val poolSize = AtomicInteger(0)
    private val maxSize = maxSize

    fun acquire(): ByteArrayOutputStream {
        val obj = pool.poll()
        return if (obj != null) {
            poolSize.decrementAndGet()
            obj.reset()
            obj
        } else {
            ByteArrayOutputStream(initialBufferSize)
        }
    }

    fun release(obj: ByteArrayOutputStream) {
        if (poolSize.get() < maxSize) {
            obj.reset()
            pool.offer(obj)
            poolSize.incrementAndGet()
        }
    }

    inline fun <R> use(block: (ByteArrayOutputStream) -> R): R {
        val obj = acquire()
        try {
            return block(obj)
        } finally {
            release(obj)
        }
    }
}

class ToolCallInfoListPool(maxSize: Int = 4) {
    private val pool = ConcurrentLinkedQueue<MutableList<ToolCallInfo>>()
    private val poolSize = AtomicInteger(0)
    private val maxSize = maxSize

    fun acquire(): MutableList<ToolCallInfo> {
        val list = pool.poll()
        return if (list != null) {
            poolSize.decrementAndGet()
            list
        } else {
            mutableListOf()
        }
    }

    fun release(list: MutableList<ToolCallInfo>) {
        if (poolSize.get() < maxSize) {
            list.clear()
            pool.offer(list)
            poolSize.incrementAndGet()
        }
    }

    inline fun <R> use(block: (MutableList<ToolCallInfo>) -> R): R {
        val list = acquire()
        try {
            return block(list)
        } finally {
            release(list)
        }
    }
}

