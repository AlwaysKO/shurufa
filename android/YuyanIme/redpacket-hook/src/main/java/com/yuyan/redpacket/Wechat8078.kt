package com.yuyan.redpacket

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/** 仅匹配已离线核验的 APK；未知版本和不完全匹配的运行类拒绝挂载。 */
object Wechat8078 {
    const val APK_SHA256 = "41f7dc1f720767fa78fa20dd13ea034b817bbf6ebd23dfd1324c647499c9c1ba"
    fun resolve(loader: ClassLoader): Binding? = runCatching {
        val int = Int::class.javaPrimitiveType!!
        val str = String::class.java
        val receive = loader.loadClass("com.tencent.mm.plugin.luckymoney.model.n6")
        val open = loader.loadClass("com.tencent.mm.plugin.luckymoney.model.h6")
        val base = loader.loadClass("com.tencent.mm.modelbase.m1")
        val dispatcher = loader.loadClass("com.tencent.mm.network.s")
        val callback = loader.loadClass("com.tencent.mm.modelbase.u0")
        val json = loader.loadClass("org.json.JSONObject")
        require(base.isAssignableFrom(receive) && base.isAssignableFrom(open) && callback.isInterface)
        val end = callback.getMethod("onSceneEnd", int, int, str, base)
        require(end.returnType == Void.TYPE)
        val dispatch = base.getMethod("dispatch", dispatcher, loader.loadClass("com.tencent.mm.network.y0"), loader.loadClass("com.tencent.mm.network.l0"))
        require(dispatch.returnType == int)
        val receiveSend = receive.getMethod("doScene", dispatcher, callback)
        val openSend = open.getMethod("doScene", dispatcher, callback)
        require(receiveSend.returnType == int && openSend.returnType == int)
        val receiveEnd = receive.getDeclaredMethod("onGYNetEnd", int, str, json)
        val openEnd = open.getDeclaredMethod("onGYNetEnd", int, str, json)
        require(receiveEnd.returnType == Void.TYPE && openEnd.returnType == Void.TYPE)
        Binding(receive.getConstructor(int, int, str, str, int, str, str),
            open.getConstructor(int, int, str, str, str, str, str, str, str, str),
            receiveEnd, openEnd, dispatch, receiveSend, openSend, dispatcher, callback)
    }.getOrNull()

    class Binding(val receiveCtor: Constructor<*>, val openCtor: Constructor<*>, val receiveEnd: Method,
                  val openEnd: Method, val dispatch: Method, private val receiveSend: Method,
                  private val openSend: Method, private val dispatcherClass: Class<*>, private val callbackClass: Class<*>) {
        private val captured = AtomicReference<Any?>()
        fun capture(value: Any?) { if (value != null && dispatcherClass.isInstance(value)) captured.set(value) }
        fun receive(packet: Packet): Any = receiveCtor.newInstance(packet.msgType, packet.channelId, packet.sendId, packet.nativeUrl, 1, "v1.0", packet.group)
        fun open(packet: Packet, token: String): Any = openCtor.newInstance(packet.msgType, packet.channelId, packet.sendId, packet.nativeUrl, "", "", packet.group, "v1.0", token, "")
        fun send(request: Any, callback: (Any, Boolean) -> Unit): Boolean {
            val dispatcher = captured.get() ?: return false
            val send = when {
                receiveCtor.declaringClass.isInstance(request) -> receiveSend
                openCtor.declaringClass.isInstance(request) -> openSend
                else -> return false
            }
            val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { self, method, args ->
                when (method.name) {
                    "equals" -> self === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "RedPacketCallback"
                    "onSceneEnd" -> {
                        if (args?.size == 4 && args[3] === request) callback(request, args[0] == 0 && args[1] == 0)
                        null
                    }
                    else -> null
                }
            }
            val result = send.invoke(request, dispatcher, proxy) as? Int ?: return false
            return result >= 0
        }
    }
}
