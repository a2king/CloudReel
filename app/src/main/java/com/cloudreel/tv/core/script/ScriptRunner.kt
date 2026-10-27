package com.cloudreel.tv.core.script

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

internal val scriptStartedAt: ThreadLocal<Long> = ThreadLocal()

class ScriptRunner(
    private val fetch: (String, Map<String, String>) -> String,
) {
    fun invoke(code: String, method: String, args: List<Any?>): String {
        if (code.length > MAX_SCRIPT) throw IllegalStateException("脚本过长")
        installFactory()
        val started = System.currentTimeMillis()
        scriptStartedAt.set(started)
        try {
            val context = Context.enter()
            try {
                val scope = context.initSafeStandardObjects()
                val host = context.newObject(scope)
                ScriptableObject.putProperty(host, "httpGet", httpFunction())
                ScriptableObject.putProperty(scope, "ctx", host)
                context.evaluateString(scope, code, "source.js", 1, null)
                val source = scope.get("source", scope)
                if (source !is Scriptable || source == Scriptable.NOT_FOUND) {
                    throw IllegalStateException("脚本需要定义 source")
                }
                val function = ScriptableObject.getProperty(source, method)
                if (function !is Function) throw IllegalStateException("脚本没有 $method")
                val callArgs = Array<Any?>(args.size + 1) { index ->
                    if (index == 0) host else Context.javaToJS(args[index - 1], scope)
                }
                return stringify(context, scope, function.call(context, scope, source, callArgs))
            } finally {
                Context.exit()
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: RhinoException) {
            throw IllegalStateException(error.message ?: "脚本执行失败")
        } finally {
            scriptStartedAt.remove()
        }
    }

    private fun httpFunction(): BaseFunction {
        return object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>?): Any {
                val started = scriptStartedAt.get()
                if (started != null && System.currentTimeMillis() - started > TIMEOUT_MS) {
                    throw IllegalStateException("脚本超时")
                }
                val url = Context.toString(args?.getOrNull(0))
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw IllegalArgumentException("只允许 http 或 https")
                }
                return fetch(url, readHeaders(args?.getOrNull(1)))
            }
        }
    }

    private fun readHeaders(value: Any?): Map<String, String> {
        if (value !is Scriptable || value == Scriptable.NOT_FOUND) return emptyMap()
        return buildMap {
            value.ids.orEmpty().take(8).forEach { id ->
                val key = id.toString()
                val raw = value.get(key, value)
                if (raw != null && raw != Scriptable.NOT_FOUND && raw !is Undefined) put(key, Context.toString(raw))
            }
        }
    }

    private fun stringify(context: Context, scope: Scriptable, result: Any?): String {
        if (result == null || result is Undefined) throw IllegalStateException("脚本没有返回数据")
        if (result is String) return result
        return Context.toString(NativeJSON.stringify(context, scope, result, null, null))
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        const val MAX_SCRIPT = 512_000
        private val lock = Any()

        @Volatile
        private var installed = false

        fun installFactory() {
            if (installed) return
            synchronized(lock) {
                if (!ContextFactory.hasExplicitGlobal()) {
                    ContextFactory.initGlobal(TimeoutFactory)
                }
                installed = true
            }
        }
    }
}

private object TimeoutFactory : ContextFactory() {
    override fun makeContext(): Context {
        val context = super.makeContext()
        context.optimizationLevel = -1
        context.setInstructionObserverThreshold(10_000)
        return context
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        val started = scriptStartedAt.get() ?: return
        if (System.currentTimeMillis() - started > 8_000L) throw Error("脚本超时")
    }
}
