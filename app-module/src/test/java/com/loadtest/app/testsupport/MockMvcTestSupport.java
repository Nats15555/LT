package com.loadtest.app.testsupport;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

public final class MockMvcTestSupport {

    private MockMvcTestSupport() {
    }

    public static UncheckedResultActions perform(MockMvc mockMvc, RequestBuilder request) {
        try {
            return new UncheckedResultActions(mockMvc.perform(request));
        } catch (Exception ex) {
            throw new AssertionError("MockMvc request failed", ex);
        }
    }

    public static final class UncheckedResultActions {
        private final ResultActions delegate;

        private UncheckedResultActions(ResultActions delegate) {
            this.delegate = delegate;
        }

        public UncheckedResultActions andExpect(ResultMatcher matcher) {
            try {
                delegate.andExpect(matcher);
            } catch (Exception ex) {
                throw new AssertionError("MockMvc assertion failed", ex);
            }
            return this;
        }
    }
}
