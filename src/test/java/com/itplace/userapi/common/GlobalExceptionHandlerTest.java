package com.itplace.userapi.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionContractController())
                .setControllerAdvice(handler)
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void missingResourceKeepsNotFoundStatus() throws Exception {
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "actuator/health");

        ResponseEntity<Object> response = handler.handleException(
                exception,
                new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isInstanceOfSatisfying(ApiResponse.class, body -> {
            assertThat(body.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(body.getData()).isNull();
        });
    }

    @Test
    void malformedJsonReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/test/errors/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
    }

    @Test
    void invalidRequestBodyReturnsValidationDetails() throws Exception {
        mockMvc.perform(post("/test/errors/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.data").value("name: 이름은 필수입니다."));
    }

    @Test
    void invalidEnumParameterReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/test/errors/enum").param("kind", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void missingParameterReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/test/errors/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void responseStatusExceptionKeepsDeclaredStatus() throws Exception {
        mockMvc.perform(get("/test/errors/status"))
                .andExpect(status().isIAmATeapot())
                .andExpect(jsonPath("$.code").value("I_AM_A_TEAPOT"))
                .andExpect(jsonPath("$.status").value("I_AM_A_TEAPOT"))
                .andExpect(jsonPath("$.data").value("의도적으로 거부된 요청입니다."));
    }

    @Test
    void serverErrorResponseStatusDoesNotExposeReason() throws Exception {
        mockMvc.perform(get("/test/errors/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowedEnvelope() throws Exception {
        mockMvc.perform(post("/test/errors/enum"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaTypeEnvelope() throws Exception {
        mockMvc.perform(post("/test/errors/body")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedExceptionReturnsInternalServerErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @RestController
    @RequestMapping("/test/errors")
    private static class ExceptionContractController {

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/enum")
        void enumParameter(@RequestParam TestKind kind) {
        }

        @GetMapping("/required")
        void requiredParameter(@RequestParam String value) {
        }

        @GetMapping("/status")
        void responseStatus() {
            throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "의도적으로 거부된 요청입니다.");
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "외부 시스템 내부 정보");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("unexpected");
        }
    }

    private record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    private enum TestKind {
        VALID
    }
}
