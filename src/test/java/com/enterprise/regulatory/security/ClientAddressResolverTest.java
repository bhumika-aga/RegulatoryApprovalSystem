package com.enterprise.regulatory.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {
    
    private static MockHttpServletRequest request(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        if (headerValue != null) {
            request.addHeader("CF-Connecting-IP", headerValue);
        }
        return request;
    }
    
    @Test
    void usesTheConnectionAddressByDefault() {
        assertThat(new ClientAddressResolver("").resolve(request("203.0.113.7"))).isEqualTo("10.0.0.5");
    }
    
    @Test
    void usesTheConfiguredHeader() {
        ClientAddressResolver resolver = new ClientAddressResolver("CF-Connecting-IP");
        assertThat(resolver.resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request(" 2001:db8::1 "))).isEqualTo("2001:db8::1");
    }
    
    @Test
    void fallsBackWhenTheHeaderIsMissingOrNotAnAddress() {
        ClientAddressResolver resolver = new ClientAddressResolver("CF-Connecting-IP");
        assertThat(resolver.resolve(request(null))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("<script>"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("1".repeat(100)))).isEqualTo("10.0.0.5");
    }
}
