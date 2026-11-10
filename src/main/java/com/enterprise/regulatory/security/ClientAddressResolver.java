package com.enterprise.regulatory.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Determines the address of the client behind a request, which login rate limiting is keyed on.
 *
 * <p>By default this is the connection's address (after Tomcat has applied {@code X-Forwarded-For}
 * from a trusted private-network proxy). Behind an edge proxy that many clients share, such as
 * Cloudflare in front of Render, every request would come from a handful of proxy addresses, so
 * {@code app.security.client-ip-header} can name the header in which that proxy passes the real
 * client address (e.g. {@code CF-Connecting-IP}). Only set it when every request passes through
 * that proxy; otherwise clients could forge the header.
 */
@Component
public class ClientAddressResolver {
    
    /**
     * An IPv4 or IPv6 address, and nothing else (bounds what an untrusted header can add).
     */
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    
    private final String clientIpHeader;
    
    public ClientAddressResolver(@Value("${app.security.client-ip-header:}") String clientIpHeader) {
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
    }
    
    public String resolve(HttpServletRequest request) {
        if (!clientIpHeader.isEmpty()) {
            String fromProxy = request.getHeader(clientIpHeader);
            if (fromProxy != null && IP_ADDRESS.matcher(fromProxy.trim()).matches()) {
                return fromProxy.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
