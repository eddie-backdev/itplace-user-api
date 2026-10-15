package com.itplace.userapi.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.inquiry.service.InquiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InquiryControllerTest {
    @Mock private InquiryService inquiryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(new InquiryController(inquiryService)).build();
    }

    @Test
    void invalidInquiryFieldsAreRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\" \",\"title\":\"x\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + "가".repeat(51) + "\",\"title\":\"x\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
        verify(inquiryService, never()).createInquiry(any());
    }

    @Test
    void malformedJsonIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":"))
                .andExpect(status().isBadRequest());
        verify(inquiryService, never()).createInquiry(any());
    }
}
