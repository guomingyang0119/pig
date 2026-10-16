package com.pig4cloud.pig.common.security.component;

import cn.hutool.core.util.StrUtil;
import com.pig4cloud.pig.common.core.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 避免前端请求伪造 inner header 直接进行服务调用，但又没有按要求配置nginx 过滤
 * <p>
 * 仅针对单体架构下生效，微服务架构下网关已经做了过滤
 *
 * @author lengleng
 * @date 2025/09/26
 */
@Slf4j
@ConditionalOnProperty(value = "security.micro", havingValue = "false")
public class PigInnerHeaderRemoveFilter extends OncePerRequestFilter implements Ordered {

	private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

	private static final List<String> FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Real-IP",
			"Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR");

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	/**
	 * 执行过滤器内部处理逻辑
	 * @param request HTTP请求对象
	 * @param response HTTP响应对象
	 * @param filterChain 过滤器链
	 * @throws ServletException 如果发生servlet相关异常
	 * @throws IOException 如果发生I/O异常
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response,
			@NotNull FilterChain filterChain) throws ServletException, IOException {
		String fromHeader = request.getHeader(SecurityConstants.FROM);
		if (StrUtil.isBlank(fromHeader)) {
			filterChain.doFilter(request, response);
			return;
		}

		// 只信任直连本机的内部调用。代理来源头可由客户端伪造，不能作为内部身份依据；
		// 本机代理转发的公网请求也不得携带内部标记，入口代理应清除 from。
		String clientIP = request.getRemoteAddr();
		boolean forwarded = FORWARDING_HEADERS.stream().anyMatch(header -> request.getHeader(header) != null);
		if (LOOPBACK_ADDRESSES.contains(clientIP) && !forwarded) {
			filterChain.doFilter(request, response);
			return;
		}

		// 非本机直连请求携带内部标记，拒绝访问。
		log.warn("请求 IP {}，疑似进行系统注入操作，请注意", clientIP);
		response.setStatus(HttpStatus.FORBIDDEN.value());
	}

}
