package com.johncolemantw.exceptionseam.analysis

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * Library and JDK classes, loaded (never initialised) from the project's compile classpath. Gives the analysis
 * type hierarchies and `throws` clauses for code whose source it does not have.
 *
 * The parent loader is the platform loader, so only the JDK running Gradle and the given classpath are visible,
 * not the plugin's own dependencies. Anything that cannot be loaded or linked is treated as unknown.
 */
class LibraryClasses(classpath: Collection<File> = emptyList()) : AutoCloseable {
    private val loader: ClassLoader = classpath.filter { it.exists() }.let { entries ->
        if (entries.isEmpty()) ClassLoader.getPlatformClassLoader()
        else URLClassLoader(entries.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
    }
    private val classes = HashMap<String, Class<*>?>()

    /** Library types a `throws` lookup was wanted for but could not be read, with the reason. */
    val problems: Map<String, String> get() = unreadable
    private val unreadable = LinkedHashMap<String, String>()

    private fun unreadable(owner: String, reason: String) {
        if ('.' in owner) unreadable.putIfAbsent(owner, reason)
    }

    /** Loads [name], also trying `Outer.Inner` as `Outer$Inner`. */
    fun load(name: String): Class<*>? = classes.getOrPut(name) {
        var candidate = name
        while (true) {
            try {
                return@getOrPut Class.forName(candidate, false, loader)
            } catch (_: Throwable) {
                val dot = candidate.lastIndexOf('.')
                if (dot < 0) return@getOrPut null
                candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1)
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /**
     * Exceptions declared by the method [name] taking [argCount] arguments on [owner] (including inherited methods),
     * or `null` when the class or method is unknown. Overloads of the same arity that differ in their `throws`
     * contribute only what they all declare, so a suggestion never names an exception the chosen overload may not throw.
     */
    fun methodThrows(owner: String, name: String, argTypes: List<String?>): List<String>? {
        val cls = load(owner) ?: return null.also { unreadable(owner, "not found on the compile classpath") }
        val methods = try {
            (cls.methods.asList() + generateSequence(cls) { it.superclass }.flatMap { it.declaredMethods.asSequence() })
                .filter { it.name == name && !java.lang.reflect.Modifier.isPrivate(it.modifiers) && accepts(it.parameterCount, it.isVarArgs, argTypes.size) }
                .distinctBy { it.toGenericString() }
        } catch (e: Throwable) {
            unreadable(owner, e.toString())
            return null
        }
        return commonThrows(narrow(methods, argTypes) { it.parameterTypes }.map { declared(it) })
    }

    /** Exceptions declared by the constructors of [owner] taking [argCount] arguments, or `null` if unknown. */
    fun constructorThrows(owner: String, argTypes: List<String?>): List<String>? {
        val cls = load(owner) ?: return null.also { unreadable(owner, "not found on the compile classpath") }
        val constructors = try {
            cls.declaredConstructors.filter {
                !java.lang.reflect.Modifier.isPrivate(it.modifiers) && accepts(it.parameterCount, it.isVarArgs, argTypes.size)
            }
        } catch (e: Throwable) {
            unreadable(owner, e.toString())
            return null
        }
        return commonThrows(narrow(constructors, argTypes) { it.parameterTypes }.map { c ->
            try { names(c.genericExceptionTypes) } catch (_: Throwable) { emptyList() }
        })
    }

    /**
     * Keeps the overloads whose parameters can take the arguments, where the argument types are known. If that would
     * leave nothing (a type we guessed wrongly), all arity matches are kept.
     */
    private fun <T> narrow(candidates: List<T>, argTypes: List<String?>, params: (T) -> Array<Class<*>>): List<T> {
        if (candidates.size < 2 || argTypes.all { it == null }) return candidates
        val fitting = candidates.filter { c ->
            val p = params(c)
            argTypes.indices.all { i ->
                val param = p.getOrNull(i) ?: p.lastOrNull()?.componentType ?: return@all true
                takes(param, argTypes[i])
            }
        }
        return fitting.ifEmpty { candidates }
    }

    private fun takes(param: Class<*>, arg: String?): Boolean {
        if (arg == null) return true
        PRIMITIVES[arg]?.let { prim ->
            if (param.isPrimitive) return param == prim.first || param.name in prim.second
            return param.isAssignableFrom(prim.third)
        }
        val argClass = load(arg) ?: return true
        return !param.isPrimitive && param.isAssignableFrom(argClass)
    }

    private fun declared(m: Method): List<String> =
        try { names(m.genericExceptionTypes) } catch (_: Throwable) { emptyList() }

    // `throws X` with a type variable erases to Throwable and says nothing about what is thrown, so it is skipped.
    private fun names(types: Array<java.lang.reflect.Type>): List<String> =
        types.mapNotNull { (it as? Class<*>)?.name?.replace('$', '.') }

    private fun commonThrows(perCandidate: List<List<String>>): List<String>? =
        if (perCandidate.isEmpty()) null
        else perCandidate.reduce { acc, next -> acc.filter { it in next } }

    private fun accepts(parameterCount: Int, varArgs: Boolean, argCount: Int) =
        parameterCount == argCount || (varArgs && argCount >= parameterCount - 1)

    private companion object {
        // primitive -> (its class, wider primitives it converts to, box)
        val PRIMITIVES: Map<String, Triple<Class<*>, Set<String>, Class<*>>> = mapOf(
            "int" to Triple(Int::class.javaPrimitiveType!!, setOf("long", "float", "double"), Integer::class.java),
            "long" to Triple(Long::class.javaPrimitiveType!!, setOf("float", "double"), java.lang.Long::class.java),
            "double" to Triple(Double::class.javaPrimitiveType!!, emptySet(), java.lang.Double::class.java),
            "float" to Triple(Float::class.javaPrimitiveType!!, setOf("double"), java.lang.Float::class.java),
            "boolean" to Triple(Boolean::class.javaPrimitiveType!!, emptySet(), java.lang.Boolean::class.java),
            "char" to Triple(Char::class.javaPrimitiveType!!, setOf("int", "long", "float", "double"), Character::class.java),
        )
    }

    override fun close() {
        (loader as? URLClassLoader)?.close()
    }
}
