package com.redclient.orbital.engine.reflect

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Tiny, error-swallowing reflection helpers used by the engine's
 * framework hooks.
 *
 * Every framework-private target we touch (`ActivityThread.mH`,
 * `ActivityThread.mInstrumentation`, `Handler.mCallback`) can move
 * between Android versions. Rather than hard-fail when a field name
 * changes, these helpers return null and let the caller log + fall back.
 */
internal object Reflect {

    /** Finds a declared field [name] on [type] or any superclass; null if missing. */
    fun field(type: Class<*>, name: String): Field? {
        var c: Class<*>? = type
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    /** Finds a declared method on [type] or any superclass; null if missing. */
    fun method(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = type
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *params).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    fun classOrNull(fqcn: String): Class<*>? = try {
        Class.forName(fqcn)
    } catch (_: ClassNotFoundException) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> read(instance: Any?, type: Class<*>, name: String): T? {
        val f = field(type, name) ?: return null
        return try { f.get(instance) as? T } catch (_: IllegalAccessException) { null }
    }

    fun write(instance: Any?, type: Class<*>, name: String, value: Any?): Boolean {
        val f = field(type, name) ?: return false
        return try { f.set(instance, value); true } catch (_: IllegalAccessException) { false }
    }
}
