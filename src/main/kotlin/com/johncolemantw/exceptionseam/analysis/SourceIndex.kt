package com.johncolemantw.exceptionseam.analysis

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.UnionType
import com.johncolemantw.exceptionseam.model.Seam
import com.github.javaparser.ast.DataKey
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.TryStmt
import java.io.File

/** A method or constructor declared in the analysed sources. */
class MethodInfo(val decl: CallableDeclaration<*>, val owner: String) {
    val name: String get() = decl.nameAsString
    val signature: String get() = "$owner#$name(${decl.parameters.joinToString(", ") { it.type.asString() }})"

    fun accepts(argCount: Int): Boolean {
        val params = decl.parameters
        if (params.size == argCount) return true
        return params.lastOrNull()?.isVarArgs == true && argCount >= params.size - 1
    }
}

/**
 * Parsed view of the project sources: types, methods, supertypes and best-effort type resolution.
 * Library types are known through [libraries]: the JDK plus the compile classpath, read by reflection.
 */
class SourceIndex private constructor(
    private val units: Map<String, CompilationUnit>,
    val libraries: LibraryClasses,
) {
    private val types = HashMap<String, TypeDeclaration<*>>()
    private val methodsByName = HashMap<String, MutableList<MethodInfo>>()
    private val constructorsByOwner = HashMap<String, MutableList<MethodInfo>>()
    private val supertypeCache = HashMap<String, List<String>>()
    private val knownSupertypes = HashMap<String, String>()

    val sourceTypes: Map<String, TypeDeclaration<*>> get() = types

    /** Registers library hierarchy edges (sub -> super) that reflection cannot see. */
    fun addKnownSupertypes(edges: Map<String, String>) {
        knownSupertypes += edges
        supertypeCache.clear()
    }

    init {
        units.values.forEach { cu ->
            cu.findAll(TypeDeclaration::class.java).forEach { t ->
                val fqn = t.fullyQualifiedName.orElse(null) ?: return@forEach
                types[fqn] = t
                t.methods.forEach { methodsByName.getOrPut(it.nameAsString) { mutableListOf() } += MethodInfo(it, fqn) }
                t.constructors.forEach { constructorsByOwner.getOrPut(fqn) { mutableListOf() } += MethodInfo(it, fqn) }
            }
        }
    }

    val compilationUnits: Map<String, CompilationUnit> get() = units

    fun unit(file: String): CompilationUnit? = units[file]

    /** The `try` statement and catch clause a seam was scanned from. */
    fun locate(seam: Seam): Pair<TryStmt, CatchClause>? {
        val tryStmt = unit(seam.file)?.findAll(TryStmt::class.java)?.firstOrNull { t ->
            t.tryBlock.range.map { it.begin.line == seam.tryBodyStartLine && it.begin.column == seam.tryBodyStartColumn }
                .orElse(false)
        } ?: return null
        val clause = tryStmt.catchClauses.firstOrNull { c ->
            c.range.map { it.begin.line == seam.line && it.begin.column == seam.column }.orElse(false)
        } ?: return null
        return tryStmt to clause
    }

    fun fileOf(node: Node): String =
        node.findCompilationUnit().map { it.getData(FILE) }.orElse(null) ?: "<unknown>"

    fun isIndexed(type: String): Boolean = type in types

    fun enclosingType(node: Node): String? =
        node.findAncestor(TypeDeclaration::class.java).orElse(null)?.fullyQualifiedName?.orElse(null)

    fun methods(name: String): List<MethodInfo> = methodsByName[name].orEmpty()

    fun constructors(owner: String): List<MethodInfo> = constructorsByOwner[owner].orEmpty()

    /** Direct supertypes (extends + implements) that could be resolved. */
    fun supertypes(type: String): List<String> = supertypeCache.getOrPut(type) {
        val decl = types[type] ?: return@getOrPut knownSupertypes[type]?.let { listOf(it) }.orEmpty()
        val refs: List<ClassOrInterfaceType> = when (decl) {
            is ClassOrInterfaceDeclaration -> decl.extendedTypes + decl.implementedTypes
            is EnumDeclaration -> decl.implementedTypes
            is RecordDeclaration -> decl.implementedTypes
            else -> emptyList()
        }
        refs.map { resolveType(it.nameWithScope, decl) }
    }

    /** [type] plus every supertype reachable through source declarations. */
    fun typeChain(type: String): List<String> {
        val seen = LinkedHashSet<String>()
        fun visit(t: String) { if (seen.add(t)) supertypes(t).forEach(::visit) }
        visit(type)
        return seen.toList()
    }

    fun subtypesOf(type: String): List<String> = types.keys.filter { it != type && isSubtype(it, type) }

    fun isSubtype(sub: String, sup: String, seen: MutableSet<String> = HashSet()): Boolean {
        if (sub == sup) return true
        if (!seen.add(sub)) return false
        val subClass = load(sub)
        val supClass = load(sup)
        if (subClass != null && supClass != null) return supClass.isAssignableFrom(subClass)
        // A type we know nothing about (typically a third-party exception) that is thrown or declared must be a
        // Throwable, and is in practice an Exception: assume the common catch-alls cover it.
        if (!isHierarchyKnown(sub) && sup in THROWABLE_OR_EXCEPTION) return true
        return supertypes(sub).any { isSubtype(it, sup, seen) }
    }

    private fun isHierarchyKnown(type: String) = type in types || type in knownSupertypes || load(type) != null

    /** `null` when the type's hierarchy cannot be traced to a known JDK class. */
    fun isChecked(type: String): Boolean? {
        val root = chain(type).firstNotNullOfOrNull(::load) ?: return null
        if (!Throwable::class.java.isAssignableFrom(root)) return null
        return !RuntimeException::class.java.isAssignableFrom(root) && !Error::class.java.isAssignableFrom(root)
    }

    private fun chain(type: String): List<String> = typeChain(type)

    /** Declared type(s) of a local variable, parameter, catch parameter or field, or `null` if unknown. */
    fun declaredTypes(name: String, ctx: Node): List<String>? {
        ctx.findAncestor(CallableDeclaration::class.java).orElse(null)?.let { callable ->
            callable.findAll(Parameter::class.java).firstOrNull { it.nameAsString == name }
                ?.let { return typesOf(it.type, ctx) }
            callable.findAll(VariableDeclarator::class.java).firstOrNull { it.nameAsString == name }
                ?.let { return typesOf(it.type, ctx) }
        }
        var owner: TypeDeclaration<*>? = ctx.findAncestor(TypeDeclaration::class.java).orElse(null)
        while (owner != null) {
            owner.fields.flatMap(FieldDeclaration::getVariables).firstOrNull { it.nameAsString == name }
                ?.let { return typesOf(it.type, owner!!) }
            owner = owner.findAncestor(TypeDeclaration::class.java).orElse(null)
        }
        return null
    }

    fun fieldTypes(name: String, ctx: Node): List<String>? {
        val owner = ctx.findAncestor(TypeDeclaration::class.java).orElse(null) ?: return null
        return owner.fields.flatMap(FieldDeclaration::getVariables).firstOrNull { it.nameAsString == name }
            ?.let { typesOf(it.type, owner) }
    }

    fun typesOf(type: Type, ctx: Node): List<String>? = when (type) {
        is UnionType -> type.elements.map { resolveType(it.asString(), ctx) }
        is ClassOrInterfaceType -> listOf(resolveType(type.nameWithScope, ctx))
        else -> null
    }

    /** Best-effort conversion of a type name as written to a fully qualified name. */
    fun resolveType(raw: String, ctx: Node): String {
        val name = raw.substringBefore('<').trim()
        if (name in types) return name
        if ('.' in name) {
            val head = name.substringBefore('.')
            val resolvedHead = resolveSimple(head, ctx)
            return if (resolvedHead != head) resolvedHead + name.substring(head.length) else name
        }
        return resolveSimple(name, ctx)
    }

    // True while a supertype name is being resolved, so that resolution does not recurse into inherited members.
    private var searchingInherited = false

    /** A nested type of a supertype (a class declared inside an implemented interface, say), by its simple name. */
    private fun inheritedMember(owner: String, name: String): String? {
        if (searchingInherited) return null
        searchingInherited = true
        try {
            for (supertype in typeChain(owner).drop(1)) {
                if ("$supertype.$name" in types || load("$supertype.$name") != null) return "$supertype.$name"
            }
        } finally {
            searchingInherited = false
        }
        return null
    }

    private fun resolveSimple(name: String, ctx: Node): String {
        var owner: TypeDeclaration<*>? = ctx.let { if (it is TypeDeclaration<*>) it else it.findAncestor(TypeDeclaration::class.java).orElse(null) }
        while (owner != null) {
            val fqn = owner.fullyQualifiedName.orElse(null)
            if (fqn != null) {
                if (owner.nameAsString == name) return fqn
                if ("$fqn.$name" in types) return "$fqn.$name"
                inheritedMember(fqn, name)?.let { return it }
            }
            owner = owner.findAncestor(TypeDeclaration::class.java).orElse(null)
        }
        val cu = ctx.findCompilationUnit().orElse(null) ?: return name
        cu.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
            ?.let { return it.nameAsString }
        val pkg = cu.packageDeclaration.map { it.nameAsString + "." }.orElse("")
        if (pkg + name in types) return pkg + name
        if (load("java.lang.$name") != null) return "java.lang.$name"
        cu.imports.filter { it.isAsterisk && !it.isStatic }.forEach {
            val candidate = it.nameAsString + "." + name
            if (candidate in types || load(candidate) != null) return candidate
        }
        return name
    }

    private fun load(name: String): Class<*>? = libraries.load(name)

    /** True when [name] is a JDK or classpath class. */
    fun isLibraryClass(name: String): Boolean = load(name) != null

    companion object {
        private val THROWABLE_OR_EXCEPTION = setOf("java.lang.Throwable", "java.lang.Exception")
        private val FILE = object : DataKey<String>() {}

        // By name because Gradle's own bundled JavaParser shadows ours on the compile classpath and has no JAVA_25.
        private fun languageLevel(): ParserConfiguration.LanguageLevel =
            runCatching { ParserConfiguration.LanguageLevel.valueOf("JAVA_25") }
                .getOrDefault(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)

        fun build(
            sourceRoots: Collection<File>,
            baseDir: File? = null,
            libraries: LibraryClasses = LibraryClasses(),
            onParseProblem: (file: File, message: String) -> Unit = { _, _ -> },
        ): SourceIndex {
            val parser = JavaParser(ParserConfiguration().setLanguageLevel(languageLevel()))
            val units = LinkedHashMap<String, CompilationUnit>()
            sourceRoots.filter { it.isDirectory }.forEach { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.forEach { file ->
                    val result = parser.parse(file)
                    if (!result.isSuccessful) onParseProblem(file, result.problems.joinToString("; ") { it.message })
                    result.result.ifPresent { cu ->
                        val relative = file.relativeTo(baseDir ?: root).invariantSeparatorsPath
                        cu.setData(FILE, relative)
                        units[relative] = cu
                    }
                }
            }
            return SourceIndex(units, libraries)
        }
    }
}
