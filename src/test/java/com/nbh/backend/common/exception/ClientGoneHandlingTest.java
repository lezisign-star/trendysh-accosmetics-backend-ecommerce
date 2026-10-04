package com.nbh.backend.common.exception;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A browser that goes away mid-response (a closed tab ends every live-update stream this way) must not reach
 * the catch-all handler: that one logs an ERROR stack trace and answers 500, so every visitor who simply
 * left would look like a server fault in the logs and the metrics.
 *
 * <p>Uses Spring's own handler lookup, the one that runs in production, so this fails if a handler is
 * removed or a more general one starts winning — no servlet container needed.
 */
class ClientGoneHandlingTest {

    private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    void aRequestThatCanNoLongerBeUsedIsHandledQuietly() {
        Method handler = resolver.resolveMethodByThrowable(new AsyncRequestNotUsableException("Response not usable after response errors."));

        assertThat(handler).isNotNull();
        assertThat(handler.getName()).isEqualTo("handleClientGone");
    }

    @Test
    void aClientAbortIsHandledQuietly() {
        Method handler = resolver.resolveMethodByThrowable(new ClientAbortException(new IOException("Broken pipe")));

        assertThat(handler).isNotNull();
        assertThat(handler.getName()).isEqualTo("handleClientGone");
    }

    @Test
    void aGenuineUnexpectedErrorStillReachesTheCatchAll() {
        assertThat(resolver.resolveMethodByThrowable(new RuntimeException("a real bug")).getName()).isEqualTo("handleUnexpected");
        assertThat(resolver.resolveMethodByThrowable(new IOException("disk full")).getName()).isEqualTo("handleUnexpected");
    }

    @Test
    void quietMeansNothingIsWrittenToTheDeadConnectionAndNoErrorStatusIsSet() throws Exception {
        Method handler = GlobalExceptionHandler.class.getMethod("handleClientGone", Exception.class);

        assertThat(handler.getReturnType()).isEqualTo(void.class);
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(handler, ResponseStatus.class);
        assertThat(status).isNotNull();
        assertThat(status.code()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThatCode(() -> new GlobalExceptionHandler().handleClientGone(new AsyncRequestNotUsableException("gone")))
                .doesNotThrowAnyException();
    }
}
