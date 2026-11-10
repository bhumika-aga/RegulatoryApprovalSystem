package com.enterprise.regulatory.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOnlyFilterTest {
    
    private static MockHttpServletRequest requestFrom(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/engine-rest/process-definition");
        request.setRemoteAddr(address);
        return request;
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"})
    void allowsLoopbackAddresses(String address) {
        assertThat(LocalOnlyFilter.isLocal(requestFrom(address))).isTrue();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5", "192.168.1.20", "203.0.113.7", "2001:db8::1", "128.0.0.1"})
    void refusesOtherAddresses(String address) {
        assertThat(LocalOnlyFilter.isLocal(requestFrom(address))).isFalse();
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For", "Forwarded"})
    void refusesProxiedRequestsEvenFromLoopback(String header) {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader(header, "203.0.113.7");
        assertThat(LocalOnlyFilter.isLocal(request)).isFalse();
    }
    
    @Test
    void answers403WithoutCallingTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new LocalOnlyFilter().doFilter(requestFrom("203.0.113.7"), response, chain);
        
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }
}
