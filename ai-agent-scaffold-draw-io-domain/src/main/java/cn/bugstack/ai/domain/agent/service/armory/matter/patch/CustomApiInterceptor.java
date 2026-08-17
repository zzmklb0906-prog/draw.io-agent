package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;
import java.net.URI;

public class CustomApiInterceptor implements ClientHttpRequestInterceptor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomApiInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        String customBaseUrl = headers.getFirst("X-Custom-Base-Url");
        String customApiKey = headers.getFirst("X-Custom-Api-Key");
        String customCompletionsPath = headers.getFirst("X-Custom-Completions-Path");

        log.info("[CustomApiInterceptor] original URL={}, customBaseUrl={}, customApiKeyPresent={}, customCompletionsPath={}",
                request.getURI(), customBaseUrl, customApiKey != null && !customApiKey.isBlank(), customCompletionsPath);

        HttpRequest modifiedRequest = request;

        if (customBaseUrl != null || customApiKey != null) {
            modifiedRequest = new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    URI originalUri = super.getURI();
                    if (customBaseUrl != null) {
                        try {
                            String path = originalUri.getPath();
                            URI customUri = new URI(customBaseUrl);
                            String customPath = customUri.getPath();
                            if (customPath == null) customPath = "";

                            // 如果用户自定义了 completionsPath，用它替换原始路径中的默认路径
                            if (customCompletionsPath != null && !customCompletionsPath.isEmpty()) {
                                // 确保自定义路径以 / 开头
                                String normalizedCustomPath = customCompletionsPath.startsWith("/") ? customCompletionsPath : "/" + customCompletionsPath;
                                // 将原始路径中的 /v1/chat/completions 或 /v1/embeddings 替换为自定义路径
                                if (path.endsWith("/chat/completions") || path.endsWith("/embeddings")) {
                                    // 保留原始路径中的 embeddings 部分判断
                                    if (path.endsWith("/embeddings")) {
                                        // embeddings 暂不替换，保持原逻辑
                                        if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                                            customPath = customPath.substring(0, customPath.length() - 3);
                                        }
                                    } else {
                                        // chat/completions: 用自定义 completionsPath 替换
                                        path = normalizedCustomPath;
                                    }
                                }
                            } else {
                                // 没有自定义 completionsPath，使用原有的去重逻辑
                                if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                                    customPath = customPath.substring(0, customPath.length() - 3);
                                }
                            }

                            String newPath = customPath + path;
                            newPath = newPath.replaceAll("//+", "/");
                            URI newUri = new URI(customUri.getScheme(), customUri.getUserInfo(), customUri.getHost(), customUri.getPort(), newPath, originalUri.getQuery(), originalUri.getFragment());
                            log.info("[CustomApiInterceptor] modified URL={}, AuthorizationPresent={}", newUri, customApiKey != null && !customApiKey.isBlank());
                            return newUri;
                        } catch (Exception e) {
                            log.error("[CustomApiInterceptor] Failed to modify URL", e);
                            return originalUri;
                        }
                    }
                    return originalUri;
                }

                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders modifiedHeaders = new HttpHeaders();
                    modifiedHeaders.putAll(super.getHeaders());
                    modifiedHeaders.remove("X-Custom-Base-Url");
                    modifiedHeaders.remove("X-Custom-Api-Key");
                    modifiedHeaders.remove("X-Custom-Completions-Path");
                    if (customApiKey != null && !customApiKey.isEmpty()) {
                        modifiedHeaders.set("Authorization", "Bearer " + customApiKey);
                    }
                    return modifiedHeaders;
                }
            };
        }

        return execution.execute(modifiedRequest, body);
    }
}
