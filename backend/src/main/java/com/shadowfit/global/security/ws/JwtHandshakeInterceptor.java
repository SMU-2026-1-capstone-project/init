package com.shadowfit.global.security.ws;

import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /ws/groups/{groupId}} 핸드셰이크 인가.
 *
 * <p>{@code /ws/**}는 {@code JwtAuthFilter}(서블릿 필터 인증)와
 * {@code security.whitelist}(시큐리티 체인 permitAll) 양쪽에서 이미 통과되도록 배선돼
 * 있다 — 브라우저 WebSocket 핸드셰이크는 커스텀 {@code Authorization} 헤더를 못 실어서다.
 * 그래서 이 기능의 인증·인가는 여기서 전담한다: 쿼리 파라미터로 받은 JWT를 검증하고,
 * 그룹의 ACTIVE 멤버인지까지 확인한 뒤에만 업그레이드를 허용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_MEMBER_ID = "memberId";
    public static final String ATTR_GROUP_ID = "groupId";

    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("/ws/groups/(\\d+)");

    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final GroupMemberRepository groupMemberRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Long groupId = extractGroupId(request.getURI().getPath());
        String token = extractToken(request);

        if (groupId == null || token == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        if (!jwtUtil.isValidToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Optional<Member> member = memberRepository.findByEmail(jwtUtil.getUserEmail(token));
        if (member.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long memberId = member.get().getId();
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, memberId, GroupMemberStatus.ACTIVE)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_MEMBER_ID, memberId);
        attributes.put(ATTR_GROUP_ID, groupId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // 핸드셰이크 이후에는 할 일이 없다 — 연결 등록은 GroupSocketHandler.afterConnectionEstablished에서 한다.
    }

    private Long extractGroupId(String path) {
        Matcher matcher = GROUP_ID_PATTERN.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        return Long.valueOf(matcher.group(1));
    }

    private String extractToken(ServerHttpRequest request) {
        return UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
    }
}