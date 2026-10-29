package com.johncolemantw.exceptionseam.scanner

import com.johncolemantw.exceptionseam.model.CatchBodyKind
import com.johncolemantw.exceptionseam.model.Seam
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.comments.Comment
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.LabeledStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.SynchronizedStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.stmt.TryStmt
import java.io.File

class JavaParserSeamScanner(
    /** Seam file paths are relative to this; defaults to each source root. */
    private val baseDir: File? = null,
    private val onParseProblem: (file: File, message: String) -> Unit = { _, _ -> },
) : SeamScanner {

    override fun scan(sourceRoots: Collection<File>): List<Seam> =
        scan(SourceIndex.build(sourceRoots, baseDir, onParseProblem = onParseProblem))

    /** Scans already-parsed sources, so a build parses each file only once. */
    fun scan(index: SourceIndex): List<Seam> =
        index.compilationUnits.flatMap { (file, cu) ->
            val comments = cu.allComments.filter { it.range.isPresent }
            cu.findAll(TryStmt::class.java).flatMap { tryStmt ->
                tryStmt.catchClauses.filterNot { isMarkedSafe(it, comments) }.map { toSeam(file, cu, tryStmt, it) }
            }.sortedWith(compareBy({ it.line }, { it.column }))
        }

    private fun toSeam(file: String, cu: CompilationUnit, tryStmt: TryStmt, clause: CatchClause): Seam {
        val param = clause.parameter
        val tryRange = tryStmt.tryBlock.range.orElse(null)
        val clauseRange = clause.range.orElse(null)
        return Seam(
            file = file,
            line = clauseRange?.begin?.line ?: -1,
            column = clauseRange?.begin?.column ?: -1,
            endLine = clauseRange?.end?.line ?: -1,
            enclosingClass = enclosingClassName(clause),
            enclosingMethod = clause.findAncestor(CallableDeclaration::class.java).orElse(null)?.signatureText(),
            caughtTypes = param.type.toUnionTypes(),
            variableName = param.nameAsString,
            bodyKind = classify(clause),
            tryBodyStartLine = tryRange?.begin?.line ?: -1,
            tryBodyStartColumn = tryRange?.begin?.column ?: -1,
            tryBodyEndLine = tryRange?.end?.line ?: -1,
            tryWithResources = tryStmt.resources.isNotEmpty(),
            nestingDepth = nestingDepth(tryStmt),
            enclosingAnnotations = enclosingAnnotations(clause),
            alwaysThrows = alwaysThrows(clause.body),
            onlyRethrows = onlyRethrows(clause),
        )
    }

    private fun com.github.javaparser.ast.type.Type.toUnionTypes(): List<String> =
        if (isUnionType) asUnionType().elements.map { it.asString() } else listOf(asString())

    private fun CallableDeclaration<*>.signatureText(): String =
        "$nameAsString(${parameters.joinToString(", ") { it.type.asString() }})"

    private fun enclosingClassName(node: Node): String? {
        val names = generateSequence(node.findAncestor(TypeDeclaration::class.java).orElse(null)) {
            it.findAncestor(TypeDeclaration::class.java).orElse(null)
        }.map { it.nameAsString }.toList().asReversed()
        if (names.isEmpty()) return null
        val pkg = node.findCompilationUnit().flatMap { it.packageDeclaration }.map { it.nameAsString + "." }.orElse("")
        return pkg + names.joinToString(".")
    }

    /** Count of enclosing try bodies or catch/finally blocks, stopping at the method boundary. */
    private fun nestingDepth(tryStmt: TryStmt): Int {
        var depth = 0
        var current: Node? = tryStmt.parentNode.orElse(null)
        while (current != null && current !is CallableDeclaration<*>) {
            if (current is TryStmt) depth++
            current = current.parentNode.orElse(null)
        }
        return depth
    }

    private fun enclosingAnnotations(node: Node): Set<String> {
        val out = linkedSetOf<String>()
        var current: Node? = node
        while (current != null) {
            if (current is CallableDeclaration<*> || current is TypeDeclaration<*>) {
                (current as BodyDeclaration<*>).annotations.mapTo(out, { it.name.identifier })
            }
            current = current.parentNode.orElse(null)
        }
        return out
    }

    private fun classify(clause: CatchClause): CatchBodyKind {
        val statements = clause.body.statements
        if (statements.isEmpty()) return CatchBodyKind.EMPTY
        val variable = clause.parameter.nameAsString

        val throws = clause.body.findAll(ThrowStmt::class.java)
        if (throws.isNotEmpty()) {
            val exprs = throws.map { it.expression }
            return when {
                exprs.any { it is NameExpr && it.nameAsString == variable } -> CatchBodyKind.RETHROW
                // `throw new X(.., e)` and `throw factory(.., e)` both carry the caught exception along.
                exprs.any { e -> newException(e) && argumentsOf(e).any { mentions(it, variable) } } -> CatchBodyKind.WRAP
                exprs.any { newException(it) } -> CatchBodyKind.THROW_NEW
                else -> CatchBodyKind.OTHER
            }
        }

        val loggingOnly = statements.all { stmt ->
            stmt is ExpressionStmt && stmt.expression.let { it is MethodCallExpr && isLogging(it, variable) }
        }
        return if (loggingOnly) CatchBodyKind.LOG_ONLY else CatchBodyKind.OTHER
    }

    /**
     * True when a comment inside the catch block starts with `exception-seam:safe`. Such a catch is not scanned at all:
     * no analysis, no findings. Comments inside a catch nested in this block belong to that catch, not this one.
     */
    private fun isMarkedSafe(clause: CatchClause, comments: List<Comment>): Boolean {
        val body = clause.body.range.orElse(null) ?: return false
        val nested = clause.body.findAll(CatchClause::class.java).mapNotNull { it.range.orElse(null) }
        return comments.any { c ->
            val r = c.range.get()
            r.begin.isAfter(body.begin) && r.end.isBefore(body.end) &&
                nested.none { n -> r.begin.isAfterOrEqual(n.begin) && r.end.isBeforeOrEqual(n.end) } &&
                SAFE.containsMatchIn(c.content)
        }
    }

    /** Whether control can only leave [stmt] by throwing. Lambdas and anonymous classes are never entered. */
    private fun alwaysThrows(stmt: Statement): Boolean = when (stmt) {
        is ThrowStmt -> true
        is BlockStmt -> stmt.statements.any { alwaysThrows(it) }
        is IfStmt -> stmt.elseStmt.map { alwaysThrows(stmt.thenStmt) && alwaysThrows(it) }.orElse(false)
        is TryStmt -> stmt.finallyBlock.map { alwaysThrows(it) }.orElse(false) ||
            (alwaysThrows(stmt.tryBlock) && stmt.catchClauses.all { alwaysThrows(it.body) })
        is SynchronizedStmt -> alwaysThrows(stmt.body)
        is LabeledStmt -> alwaysThrows(stmt.statement)
        else -> false
    }

    /** Throws on every path, and every throw of the block itself is `throw <caught variable>`. */
    private fun onlyRethrows(clause: CatchClause): Boolean {
        if (!alwaysThrows(clause.body)) return false
        val variable = clause.parameter.nameAsString
        // Throws inside lambdas and local or anonymous classes are not the block's own.
        val own = clause.body.findAll(ThrowStmt::class.java).filter { t ->
            generateSequence(t.parentNode.orElse(null)) { it.parentNode.orElse(null) }
                .takeWhile { it !== clause.body }
                .none { it is LambdaExpr || it is BodyDeclaration<*> }
        }
        return own.isNotEmpty() && own.all { val e = it.expression; e is NameExpr && e.nameAsString == variable }
    }

    // A thrown `new X(..)` or a call that builds the exception, such as `throw refused(..)`.
    private fun newException(e: Expression) = e is ObjectCreationExpr || e is MethodCallExpr

    private fun argumentsOf(e: Expression): List<Expression> = when (e) {
        is ObjectCreationExpr -> e.arguments
        is MethodCallExpr -> e.arguments
        else -> emptyList()
    }

    private fun mentions(arg: Expression, variable: String) =
        arg.findAll(NameExpr::class.java).any { it.nameAsString == variable }

    private fun isLogging(call: MethodCallExpr, variable: String): Boolean {
        val scope = call.scope.orElse(null) ?: return false
        if (call.nameAsString == "printStackTrace" && scope is NameExpr && scope.nameAsString == variable) return true
        return when (scope) {
            is NameExpr -> scope.nameAsString.lowercase() in LOGGER_NAMES
            is FieldAccessExpr ->
                scope.scope.toString() == "System" && scope.nameAsString in setOf("out", "err")
            else -> false
        }
    }

    private companion object {
        // The comment text must start with the marker; whatever follows is a free-text reason.
        val SAFE = Regex("""^\s*exception-seam:safe\b""", RegexOption.IGNORE_CASE)
        val LOGGER_NAMES = setOf("log", "logger", "slf4j", "logging")
    }
}
