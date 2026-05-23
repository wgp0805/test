package com.blog.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/business")
        public String boom() {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        @GetMapping("/probe/unknown")
        public String crash() {
            throw new RuntimeException("boom");
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    @Test
    void business_exception_returns_business_code() throws Exception {
        mvc.perform(get("/probe/business").accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(1001))
           .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void unknown_exception_returns_9500() throws Exception {
        mvc.perform(get("/probe/unknown").accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(9500));
    }
}
