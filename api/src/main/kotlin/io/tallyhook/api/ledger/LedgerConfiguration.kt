// This file configures UTC time and RFC 7807 problem responses for the ledger API.
package io.tallyhook.api.ledger

import io.tallyhook.ledger.LedgerError
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Clock

@org.springframework.context.annotation.Configuration
class LedgerConfiguration {
    @org.springframework.context.annotation.Bean
    fun clock(): Clock = Clock.systemUTC()
}

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class LedgerProblemHandler {
    @ExceptionHandler(LedgerApiException::class)
    fun ledgerError(
        exception: LedgerApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(exception.status, exception.code, exception.detail, request)

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "missing_header", "A required request header is missing.", request)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedRequest(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "malformed_request", "The request body is malformed.", request)

    @ExceptionHandler(MethodArgumentNotValidException::class, ConstraintViolationException::class)
    fun validationFailure(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Request validation failed.", request)

    @ExceptionHandler(DuplicateKeyException::class)
    fun duplicateKey(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "conflict", "The operation conflicts with an existing ledger record.", request)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrityFailure(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "ledger_invariant_violation",
            "The operation violates a ledger invariant.",
            request,
        )

    @ExceptionHandler(DataAccessException::class, TransactionSystemException::class)
    fun persistenceFailure(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "persistence_error",
            "The ledger operation could not be completed.",
            request,
        )

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail)
        body.title = status.reasonPhrase
        body.type = URI.create("https://tallyhook.dev/problems/$code")
        body.instance = URI.create(request.requestURI)
        body.setProperty("code", code)
        return ResponseEntity
            .status(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .body(body)
    }
}

internal fun ledgerFailure(error: LedgerError): Nothing = throw LedgerApiException.from(error)

internal fun invalidRequest(detail: String): Nothing =
    throw LedgerApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", detail)
