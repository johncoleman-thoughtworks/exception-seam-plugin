package com.johncolemantw.exceptionseam.analysis

import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.ReachableException
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.TextBlockLiteralExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.stmt.TryStmt

/** A resolved (or partly resolved) method call, handed to [CallRules]. */
class CallSite(val node: MethodCallExpr, val receiverTypes: List<String>?, val resolved: List<MethodInfo>)

class RuleHit(val type: String, val reason: String)

/** Framework knowledge: exceptions a call can throw that the source does not show. */
fun interface CallRules {
    fun extraExceptions(site: CallSite): List<RuleHit>
}

/**
 * Bounded walk over the AST collecting exceptions that can escape a region of code.
 *
 * Deliberately approximate: calls are resolved by name, arity and (where known) the receiver's declared
 * type, only among the analysed sources. Lambda bodies, anonymous and local classes are not entered.
 */
class CallGraphWalker(
    private val index: SourceIndex,
    private val config: AnalysisConfig,
    private val rules: CallRules? = null,
) {

    private data class Frame(val depth: Int, val path: List<String>, val stack: Set<String>)

    fun reachableFrom(roots: List<Node>): List<ReachableException> {
        val out = mutableListOf<ReachableException>()
        roots.forEach { walk(it, Frame(0, emptyList(), emptySet()), out) }
        return out
    }

    private fun walk(node: Node, frame: Frame, out: MutableList<ReachableException>) {
        when (node) {
            is LambdaExpr, is LocalClassDeclarationStmt, is LocalRecordDeclarationStmt -> return
            is TryStmt -> walkTry(node, frame, out)
            is ThrowStmt -> {
                thrownTypes(node).forEach { out += exception(it, ExceptionOrigin.THROW_STATEMENT, node, frame) }
                children(node, frame, out)
            }
            is MethodCallExpr -> {
                children(node, frame, out)
                val resolution = resolveCall(node)
                resolution.methods.forEach { enter(it, frame, out) }
                if (config.libraryThrows && resolution.methods.isEmpty()) {
                    libraryThrows(node, resolution, frame, out)
                }
                rules?.extraExceptions(CallSite(node, resolution.receiver, resolution.methods))?.forEach {
                    out += exception(it.type, ExceptionOrigin.FRAMEWORK_RULE, node, frame, it.reason)
                }
            }
            is ObjectCreationExpr -> {
                children(node, frame, out)
                val type = index.resolveType(node.type.nameWithScope, node)
                val constructors = index.constructors(type).filter { it.accepts(node.arguments.size) }
                constructors.forEach { enter(it, frame, out) }
                if (config.libraryThrows && constructors.isEmpty() && !index.isIndexed(type)) {
                    index.libraries.constructorThrows(type, argTypes(node.arguments)).orEmpty().forEach {
                        val label = "${type.substringAfterLast('.')}#<init>"
                        out += exception(
                            it, ExceptionOrigin.DECLARED_THROWS, node, frame.copy(path = frame.path + label),
                            "declared by the $label constructor (classpath)",
                        )
                    }
                }
            }
            else -> children(node, frame, out)
        }
    }

    private fun children(node: Node, frame: Frame, out: MutableList<ReachableException>) {
        // Anonymous class bodies belong to another type; only the constructor arguments run here.
        val skip = (node as? ObjectCreationExpr)?.anonymousClassBody?.orElse(null)
        node.childNodes.toList().forEach { child ->
            if (skip == null || child !in skip) walk(child, frame, out)
        }
    }

    private fun walkTry(node: TryStmt, frame: Frame, out: MutableList<ReachableException>) {
        val inner = mutableListOf<ReachableException>()
        node.resources.forEach { walk(it, frame, inner) }
        walk(node.tryBlock, frame, inner)
        val caught = node.catchClauses.flatMap { index.typesOf(it.parameter.type, it).orEmpty() }
        out += inner.filterNot { e -> caught.any { index.isSubtype(e.type, it) } }
        node.catchClauses.forEach { walk(it.body, frame, out) }
        node.finallyBlock.ifPresent { walk(it, frame, out) }
    }

    private fun enter(callee: MethodInfo, frame: Frame, out: MutableList<ReachableException>) {
        if (config.isExcluded(callee.owner)) return
        val path = frame.path + callee.signature
        callee.decl.thrownExceptions.forEach { t ->
            out += exception(index.resolveType(t.asString(), callee.decl), ExceptionOrigin.DECLARED_THROWS, t, frame.copy(path = path))
        }
        if (frame.depth + 1 > config.maxCallDepth || callee.signature in frame.stack) return
        val body = when (val d = callee.decl) {
            is MethodDeclaration -> d.body.orElse(null)
            is ConstructorDeclaration -> d.body
            else -> null
        } ?: return
        walk(body, Frame(frame.depth + 1, path, frame.stack + callee.signature), out)
    }

    private fun exception(type: String, origin: ExceptionOrigin, at: Node, frame: Frame, reason: String? = null) = ReachableException(
        type = type,
        origin = origin,
        checked = index.isChecked(type),
        file = index.fileOf(at),
        line = at.range.map { it.begin.line }.orElse(-1),
        callPath = frame.path,
        reason = reason,
    )

    private fun thrownTypes(stmt: ThrowStmt): List<String> = when (val e = stmt.expression) {
        is ObjectCreationExpr -> listOf(index.resolveType(e.type.nameWithScope, stmt))
        is CastExpr -> index.typesOf(e.type, stmt).orEmpty()
        is NameExpr -> index.declaredTypes(e.nameAsString, stmt).orEmpty()
        is MethodCallExpr -> builtByHelper(e)
        else -> emptyList()
    }

    /**
     * `throw reject(..)` where `reject` is a project method that builds the exception: the declared return type is what
     * gets thrown. Only types the index or the classpath knows are kept, so a type variable is not mistaken for one.
     */
    private fun builtByHelper(call: MethodCallExpr): List<String> =
        resolveCall(call).methods.mapNotNull { it.decl as? MethodDeclaration }
            .flatMap { index.typesOf(it.type, it).orEmpty() }
            .filter { index.isIndexed(it) || index.isLibraryClass(it) }
            .distinct()

    /** Adds what the library method [call] declares, using the first receiver type that has such a method. */
    private fun libraryThrows(call: MethodCallExpr, resolution: Resolution, frame: Frame, out: MutableList<ReachableException>) {
        val owners = resolution.receiver.orEmpty().flatMap { index.typeChain(it) }.distinct().filterNot { index.isIndexed(it) }
        for (owner in owners) {
            val declared = index.libraries.methodThrows(owner, call.nameAsString, argTypes(call.arguments)) ?: continue
            val label = "${owner.substringAfterLast('.')}#${call.nameAsString}"
            declared.forEach {
                out += exception(
                    it, ExceptionOrigin.DECLARED_THROWS, call, frame.copy(path = frame.path + label),
                    "declared by $label (classpath)",
                )
            }
            return
        }
    }

    /** Best-effort static type of each argument (fully qualified or a primitive name); `null` when unknown. */
    private fun argTypes(args: List<Expression>): List<String?> = args.map { arg ->
        when (arg) {
            is StringLiteralExpr, is TextBlockLiteralExpr -> "java.lang.String"
            is IntegerLiteralExpr -> "int"
            is LongLiteralExpr -> "long"
            is DoubleLiteralExpr -> if (arg.value.endsWith("f", true)) "float" else "double"
            is BooleanLiteralExpr -> "boolean"
            is CharLiteralExpr -> "char"
            is ClassExpr -> "java.lang.Class"
            is CastExpr -> index.typesOf(arg.type, arg)?.singleOrNull()
            is ObjectCreationExpr -> index.resolveType(arg.type.nameWithScope, arg)
            is NameExpr -> index.declaredTypes(arg.nameAsString, arg)?.singleOrNull()
            else -> null
        }
    }

    private class Resolution(val receiver: List<String>?, val methods: List<MethodInfo>)

    private fun resolveCall(call: MethodCallExpr): Resolution {
        val owner = index.enclosingType(call)
        val receiver: List<String>? = when (val scope = call.scope.orElse(null)) {
            null, is ThisExpr -> owner?.let { listOf(it) }
            is SuperExpr -> owner?.let { index.supertypes(it) }
            is NameExpr ->
                index.declaredTypes(scope.nameAsString, call)
                    ?: index.resolveType(scope.nameAsString, call)
                        .takeIf { index.isIndexed(it) || (config.libraryThrows && index.isLibraryClass(it)) }
                        ?.let { listOf(it) }
            is FieldAccessExpr ->
                if (scope.scope is ThisExpr) index.fieldTypes(scope.nameAsString, call) else null
            else -> null
        }
        val candidates = index.methods(call.nameAsString).filter { it.accepts(call.arguments.size) }
        if (candidates.isEmpty()) return Resolution(receiver, emptyList())
        if (receiver == null) {
            // Unknown receiver (chained call etc.): only accept an unambiguous match.
            return Resolution(null, candidates.singleOrNull()?.let { listOf(it) } ?: emptyList())
        }
        // Declared type, its supertypes, and any source implementation that could receive the call.
        val applicable = receiver.flatMap { index.typeChain(it) + index.subtypesOf(it) }.toSet()
        return Resolution(receiver, candidates.filter { it.owner in applicable })
    }
}
