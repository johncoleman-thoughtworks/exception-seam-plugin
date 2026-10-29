package com.johncolemantw.exceptionseam.analysis

import com.johncolemantw.exceptionseam.model.ReachableException
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations

/**
 * Static approximation of Spring behaviour that is invisible in source:
 *
 * - calls into a `@Transactional` bean may fail at commit ([TX]);
 * - Spring Data repositories, `JdbcTemplate` and `@Repository` beans throw [DAE];
 * - calls into a `@Validated` bean may throw `ConstraintViolationException`;
 * - exceptions already covered by a `@ControllerAdvice` / `@ExceptionHandler` are marked as handled.
 *
 * Rules apply only when the call crosses a bean boundary (caller type != callee type), because a
 * self-invocation bypasses the Spring proxy.
 */
class SpringRules(private val index: SourceIndex) : CallRules {

    init {
        index.addKnownSupertypes(HIERARCHY)
    }

    private class Handler(val owner: String, val method: String, val types: List<String>, val global: Boolean) {
        val name get() = "$owner#$method"
    }

    private val handlers: List<Handler> by lazy {
        index.sourceTypes.flatMap { (fqn, type) ->
            val advice = type.has("ControllerAdvice") || type.has("RestControllerAdvice")
            type.methods.filter { it.has("ExceptionHandler") }
                .map { Handler(fqn, it.nameAsString, handlerTypes(it), advice) }
        }
    }

    override fun extraExceptions(site: CallSite): List<RuleHit> {
        val caller = index.enclosingType(site.node)
        val hits = LinkedHashMap<String, RuleHit>()
        fun hit(type: String, reason: String) { hits.putIfAbsent(type, RuleHit(type, reason)) }

        for (m in site.resolved.filter { it.owner != caller }) {
            val owner = index.sourceTypes[m.owner]
            if (m.decl.has("Transactional") || owner?.has("Transactional") == true) {
                hit(TX, "call into @Transactional ${m.owner}#${m.name} can fail at commit")
            }
            if (m.decl.has("Validated") || owner?.has("Validated") == true) {
                hit(constraintViolation(m), "${m.owner} is @Validated")
            }
            if (owner?.has("Repository") == true) hit(DAE, "${m.owner} is a @Repository bean")
        }

        site.receiverTypes.orEmpty()
            .filter { it != caller }
            .flatMap { index.typeChain(it) }
            .firstOrNull(::isDataAccessType)
            ?.let { hit(DAE, "$it call is translated to DataAccessException") }

        return hits.values.toList()
    }

    /** Marks exceptions that a specific (non catch-all) `@ExceptionHandler` already covers. */
    fun markHandled(found: List<ReachableException>, seamClass: String?): List<ReachableException> {
        if (handlers.isEmpty()) return found
        return found.map { e ->
            val handler = handlers.firstOrNull { h ->
                (h.global || h.owner == seamClass) && h.types.any { it !in CATCH_ALL && index.isSubtype(e.type, it) }
            }
            if (handler == null) e else e.copy(handledBy = handler.name)
        }
    }

    private fun isDataAccessType(type: String): Boolean =
        (type.startsWith("org.springframework.data.") && type.endsWith("Repository")) ||
            (type.startsWith("org.springframework.jdbc.core.") && type.substringAfterLast('.') in JDBC_TYPES)

    private fun constraintViolation(m: MethodInfo): String {
        val javax = m.decl.findCompilationUnit().map { cu -> cu.imports.any { it.nameAsString.startsWith("javax.validation") } }
            .orElse(false)
        return (if (javax) "javax" else "jakarta") + ".validation.ConstraintViolationException"
    }

    private fun handlerTypes(method: MethodDeclaration): List<String> {
        val annotation = method.annotations.first { it.name.identifier == "ExceptionHandler" }
        val value: Expression? = when (annotation) {
            is SingleMemberAnnotationExpr -> annotation.memberValue
            is NormalAnnotationExpr -> annotation.pairs.firstOrNull { it.nameAsString == "value" }?.value
            else -> null
        }
        val expressions = when (value) {
            null -> emptyList()
            is ArrayInitializerExpr -> value.values.toList()
            else -> listOf(value)
        }
        val declared = expressions.filterIsInstance<ClassExpr>().map { index.resolveType(it.type.asString(), method) }
        if (declared.isNotEmpty()) return declared
        return method.parameters.flatMap { index.typesOf(it.type, method).orEmpty() }
            .filter { index.isSubtype(it, "java.lang.Throwable") }
    }

    private fun NodeWithAnnotations<*>.has(name: String) = annotations.any { it.name.identifier == name }

    private fun TypeDeclaration<*>.has(name: String) = (this as NodeWithAnnotations<*>).has(name)

    private fun com.github.javaparser.ast.body.CallableDeclaration<*>.has(name: String) =
        (this as NodeWithAnnotations<*>).has(name)

    companion object {
        const val TX = "org.springframework.transaction.TransactionException"
        const val DAE = "org.springframework.dao.DataAccessException"
        private const val NRE = "org.springframework.core.NestedRuntimeException"
        private const val DA = "org.springframework.dao"
        private const val TXP = "org.springframework.transaction"

        private val CATCH_ALL = setOf("java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException")
        private val JDBC_TYPES = setOf(
            "JdbcTemplate", "JdbcOperations", "NamedParameterJdbcTemplate", "NamedParameterJdbcOperations", "JdbcClient",
        )

        /** Subset of the Spring (sub -> super) exception hierarchy that matters for catch analysis. */
        val HIERARCHY: Map<String, String> = mapOf(
            TX to NRE,
            "$TXP.TransactionSystemException" to TX,
            "$TXP.UnexpectedRollbackException" to TX,
            "$TXP.CannotCreateTransactionException" to TX,
            "$TXP.TransactionTimedOutException" to TX,
            DAE to NRE,
            NRE to "java.lang.RuntimeException",
            "$DA.NonTransientDataAccessException" to DAE,
            "$DA.TransientDataAccessException" to DAE,
            "$DA.RecoverableDataAccessException" to DAE,
            "$DA.DataIntegrityViolationException" to "$DA.NonTransientDataAccessException",
            "$DA.DuplicateKeyException" to "$DA.DataIntegrityViolationException",
            "$DA.DataRetrievalFailureException" to "$DA.NonTransientDataAccessException",
            "$DA.IncorrectResultSizeDataAccessException" to "$DA.DataRetrievalFailureException",
            "$DA.EmptyResultDataAccessException" to "$DA.IncorrectResultSizeDataAccessException",
            "$DA.InvalidDataAccessResourceUsageException" to "$DA.NonTransientDataAccessException",
            "$DA.InvalidDataAccessApiUsageException" to "$DA.NonTransientDataAccessException",
            "$DA.ConcurrencyFailureException" to "$DA.TransientDataAccessException",
            "$DA.OptimisticLockingFailureException" to "$DA.ConcurrencyFailureException",
            "$DA.PessimisticLockingFailureException" to "$DA.ConcurrencyFailureException",
            "$DA.CannotAcquireLockException" to "$DA.PessimisticLockingFailureException",
            "$DA.QueryTimeoutException" to "$DA.TransientDataAccessException",
            "jakarta.validation.ConstraintViolationException" to "jakarta.validation.ValidationException",
            "jakarta.validation.ValidationException" to "java.lang.RuntimeException",
            "javax.validation.ConstraintViolationException" to "javax.validation.ValidationException",
            "javax.validation.ValidationException" to "java.lang.RuntimeException",
        )
    }
}
