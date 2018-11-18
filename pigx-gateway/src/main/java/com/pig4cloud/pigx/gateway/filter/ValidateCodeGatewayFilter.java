/*
 *    Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 */

package com.pig4cloud.pigx.gateway.filter;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pigx.common.core.constant.CommonConstant;
import com.pig4cloud.pigx.common.core.constant.SecurityConstants;
import com.pig4cloud.pigx.common.core.exception.CheckedException;
import com.pig4cloud.pigx.common.core.exception.ValidateCodeException;
import com.pig4cloud.pigx.common.core.util.R;
import com.pig4cloud.pigx.gateway.config.FilterIgnorePropertiesConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;

/**
 * @author lengleng
 * @date 2018/7/4
 * 验证码处理
 */
@Slf4j
@Component
@AllArgsConstructor
public class ValidateCodeGatewayFilter extends AbstractGatewayFilterFactory {
	private static final String BASIC_ = "Basic ";
	private final ObjectMapper objectMapper;
	private final RedisTemplate redisTemplate;
	private final FilterIgnorePropertiesConfig filterIgnorePropertiesConfig;

	/**
	 * 从header 请求中的clientId/clientsecect
	 *
	 * @param header header中的参数
	 * @throws CheckedException if the Basic header is not present or is not valid
	 *                          Base64
	 */
	public static String[] extractAndDecodeHeader(String header)
		throws IOException, CheckedException {

		byte[] base64Token = header.substring(6).getBytes("UTF-8");
		byte[] decoded;
		try {
			decoded = Base64.decode(base64Token);
		} catch (IllegalArgumentException e) {
			throw new CheckedException(
				"Failed to decode basic authentication token");
		}

		String token = new String(decoded, CharsetUtil.UTF_8);

		int delim = token.indexOf(":");

		if (delim == -1) {
			throw new CheckedException("Invalid basic authentication token");
		}
		return new String[]{token.substring(0, delim), token.substring(delim + 1)};
	}

	/**
	 * *从header 请求中的clientId/clientsecect
	 *
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static String[] extractAndDecodeHeader(ServerHttpRequest request)
		throws IOException, CheckedException {
		String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

		if (header == null || !header.startsWith(BASIC_)) {
			throw new CheckedException("请求头中client信息为空");
		}

		return extractAndDecodeHeader(header);
	}

	@Override
	public GatewayFilter apply(Object config) {
		return (exchange, chain) -> {
			ServerHttpRequest request = exchange.getRequest();

			// 不是登录请求，直接向下执行
			if (!StrUtil.containsAnyIgnoreCase(request.getURI().getPath()
				, SecurityConstants.OAUTH_TOKEN_URL, SecurityConstants.SMS_TOKEN_URL)) {
				return chain.filter(exchange);
			}

			// 终端设置不校验， 直接向下执行(1. 从请求参数中获取 2.从header取)
			String clientId = request.getQueryParams().getFirst("client_id");
			if (StrUtil.isNotBlank(clientId)
				&& filterIgnorePropertiesConfig.getClients().contains(clientId)) {
				return chain.filter(exchange);
			}

			try {
				String[] clientInfos = extractAndDecodeHeader(request);
				if (filterIgnorePropertiesConfig.getClients().contains(clientInfos[0])) {
					return chain.filter(exchange);
				}

				//校验验证码
				checkCode(request);
			} catch (Exception e) {
				ServerHttpResponse response = exchange.getResponse();
				response.setStatusCode(HttpStatus.PRECONDITION_REQUIRED);
				try {
					return response.writeWith(Mono.just(response.bufferFactory()
						.wrap(objectMapper.writeValueAsBytes(new R<>(e)))));
				} catch (JsonProcessingException e1) {
					log.error("对象输出异常", e1);
				}
			}

			return chain.filter(exchange);
		};
	}

	/**
	 * 检查code
	 *
	 * @param request
	 * @throws ValidateCodeException 校验异常
	 */
	private void checkCode(ServerHttpRequest request) throws ValidateCodeException {
		String code = request.getQueryParams().getFirst("code");

		if (StrUtil.isBlank(code)) {
			throw new ValidateCodeException();
		}

		String randomStr = request.getQueryParams().getFirst("randomStr");
		if (StrUtil.isBlank(randomStr)) {
			randomStr = request.getQueryParams().getFirst("mobile");
		}

		String key = CommonConstant.DEFAULT_CODE_KEY + randomStr;
		if (!redisTemplate.hasKey(key)) {
			throw new ValidateCodeException();
		}

		Object codeObj = redisTemplate.opsForValue().get(key);

		if (codeObj == null) {
			throw new ValidateCodeException();
		}

		String saveCode = codeObj.toString();
		if (StrUtil.isBlank(saveCode)) {
			redisTemplate.delete(key);
			throw new ValidateCodeException();
		}

		if (!StrUtil.equals(saveCode, code)) {
			redisTemplate.delete(key);
			throw new ValidateCodeException();
		}

		redisTemplate.delete(key);
	}
}
