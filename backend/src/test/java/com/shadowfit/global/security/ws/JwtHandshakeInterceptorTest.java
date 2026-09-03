package com.shadowfit.global.security.ws;

import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /ws/groups/{groupId}} 핸드셰이크 인가 — JWT 검증부터 ACTIVE 멤버십 확인까지 이
 * 인터셉터가 전담한다({@code JwtAuthFilter}는 {@code /ws/**}를 그냥 통과시킨다).
 */
@DisplayName("JwtHandshakeInterceptor 테스트")
class JwtHandshakeInterceptorTest {

    private static final Long GROUP_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final String TOKEN = "valid-token";

    @Mock private JwtUtil jwtUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private WebSocketHandler wsHandler;

    private JwtHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new JwtHandshakeInterceptor(jwtUtil, memberRepository, groupMemberRepository);
    }

    @Test
    @DisplayName("경로에 groupId가 없으면 400 반환하고 업그레이드를 거부한다")
    void beforeHandshake_pathWithoutGroupId_returns400() {
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        boolean result = handshake("/ws/other", "token=" + TOKEN, mockResponse);

        assertThat(result).isFalse();
        assertThat(mockResponse.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("token 쿼리 파라미터가 없으면 400")
    void beforeHandshake_missingToken_returns400() {
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        boolean result = handshake("/ws/groups/" + GROUP_ID, null, mockResponse);

        assertThat(result).isFalse();
        assertThat(mockResponse.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("토큰이 유효하지 않으면 401")
    void beforeHandshake_invalidToken_returns401() {
        when(jwtUtil.isValidToken(TOKEN)).thenReturn(false);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        boolean result = handshake("/ws/groups/" + GROUP_ID, "token=" + TOKEN, mockResponse);

        assertThat(result).isFalse();
        assertThat(mockResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("토큰은 유효하지만 이메일에 해당하는 회원이 없으면 401")
    void beforeHandshake_memberNotFound_returns401() {
        when(jwtUtil.isValidToken(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserEmail(TOKEN)).thenReturn("ghost@test.com");
        when(memberRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        boolean result = handshake("/ws/groups/" + GROUP_ID, "token=" + TOKEN, mockResponse);

        assertThat(result).isFalse();
        assertThat(mockResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("회원은 맞지만 해당 그룹의 ACTIVE 멤버가 아니면 403")
    void beforeHandshake_notActiveGroupMember_returns403() {
        stubValidMember();
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(false);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        boolean result = handshake("/ws/groups/" + GROUP_ID, "token=" + TOKEN, mockResponse);

        assertThat(result).isFalse();
        assertThat(mockResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("ACTIVE 멤버면 업그레이드를 허용하고 attributes에 groupId·memberId를 심는다")
    void beforeHandshake_activeGroupMember_allowsAndPopulatesAttributes() {
        stubValidMember();
        when(groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(GROUP_ID, MEMBER_ID, GroupMemberStatus.ACTIVE))
                .thenReturn(true);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean result = handshake("/ws/groups/" + GROUP_ID, "token=" + TOKEN, mockResponse, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry(JwtHandshakeInterceptor.ATTR_GROUP_ID, GROUP_ID);
        assertThat(attributes).containsEntry(JwtHandshakeInterceptor.ATTR_MEMBER_ID, MEMBER_ID);
    }

    private void stubValidMember() {
        when(jwtUtil.isValidToken(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserEmail(TOKEN)).thenReturn("member@test.com");
        Member member = Member.builder().id(MEMBER_ID).email("member@test.com").username("member")
                .password("encoded-password").role(UserRole.USER).build();
        when(memberRepository.findByEmail("member@test.com")).thenReturn(Optional.of(member));
    }

    private boolean handshake(String path, String queryString, MockHttpServletResponse mockResponse) {
        return handshake(path, queryString, mockResponse, new HashMap<>());
    }

    private boolean handshake(String path, String queryString, MockHttpServletResponse mockResponse,
                               Map<String, Object> attributes) {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", path);
        mockRequest.setScheme("http");
        mockRequest.setServerName("localhost");
        mockRequest.setServerPort(80);
        if (queryString != null) {
            mockRequest.setQueryString(queryString);
        }
        ServletServerHttpRequest request = new ServletServerHttpRequest(mockRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(mockResponse);

        return interceptor.beforeHandshake(request, response, wsHandler, attributes);
    }
}
