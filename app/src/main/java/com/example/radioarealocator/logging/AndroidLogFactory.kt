package com.example.radioarealocator.logging

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogConfigurationException
import org.apache.commons.logging.LogFactory

/**
 * 自定义的 commons-logging [LogFactory]，直接返回 [AndroidLog] 实例。
 *
 * 通过 SPI（META-INF/services/org.apache.commons.logging.LogFactory）注册，
 * 使 commons-logging 的 [LogFactory.getFactory] 直接使用本工厂，完全绕过
 * 默认的 LogFactoryImpl 及其在 Android 上会抛 NPE 的日志实现发现流程。
 */
class AndroidLogFactory : LogFactory() {

    override fun getInstance(clazz: Class<*>): Log = AndroidLog(clazz.simpleName)

    override fun getInstance(name: String): Log = AndroidLog(name)

    override fun setAttribute(name: String?, value: Any?) {
        // 无属性支持
    }

    override fun getAttribute(name: String?): Any? = null

    @Suppress("UNCHECKED_CAST")
    override fun getAttributeNames(): Array<String> = emptyArray()

    override fun removeAttribute(name: String?) {
        // 无属性支持
    }

    override fun release() {
        // 无资源需要释放
    }
}
