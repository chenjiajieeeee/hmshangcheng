package com.hmall.gateway.filter;

import cn.hutool.core.text.AntPathMatcher;
import com.hmall.common.exception.UnauthorizedException;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;

    private final JwtTool jwtTool;

    private final AntPathMatcher antPathMatcher=new AntPathMatcher();
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.获取request
        final ServerHttpRequest request = exchange.getRequest();
        //2.获取是否需要拦截
        if(isExculde(request.getPath().toString())) {
            //2.1、放行
            return chain.filter(exchange);
        }
        //3.校验并解析token
        String token=null;
        final List<String> authorization = request.getHeaders().get("authorization");
        if(null!=authorization&&!authorization.isEmpty()) token=authorization.get(0);
        Long userId=null;
        try {
            userId = jwtTool.parseToken(token);
            System.out.println(userId);
        } catch (UnauthorizedException e) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        final String userInfo = userId.toString();
        final ServerWebExchange build = exchange.mutate()
                .request(builder -> builder.header("user-info", userInfo))
                .build();
        return chain.filter(build);
    }

    private boolean isExculde(String path) {
        final List<String> collect = authProperties.getExcludePaths().
                stream().
                filter(pathPatter -> antPathMatcher.match(pathPatter, path)).
                collect(Collectors.toList());
        return !collect.isEmpty();

    }

    @Override
    public int getOrder() {
        return 0;
    }
}
