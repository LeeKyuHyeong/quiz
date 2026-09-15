package com.kh.game.service;

import com.kh.game.entity.Member;
import com.kh.game.exception.BusinessException;
import com.kh.game.repository.GameRoomParticipantRepository;
import com.kh.game.repository.GameSessionRepository;
import com.kh.game.repository.MemberLoginHistoryRepository;
import com.kh.game.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService 비밀번호 초기화·재설정")
class MemberServicePasswordTest {

    @Mock MemberRepository memberRepository;
    @Mock MemberLoginHistoryRepository loginHistoryRepository;
    @Mock GameRoomParticipantRepository participantRepository;
    @Mock GameSessionRepository gameSessionRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock BrevoMailClient mailClient;

    MemberService memberService;
    Member target;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, loginHistoryRepository,
                participantRepository, gameSessionRepository, passwordEncoder, mailClient);
        target = new Member();
        target.setId(10L);
        target.setEmail("user@test.com");
        target.setPassword("OLD_HASH");
    }

    @Nested
    @DisplayName("issueTemporaryPassword — 관리자 초기화")
    class IssueTemporaryPassword {

        @Test
        @DisplayName("임시 비밀번호를 메일로 보내고, 같은 값을 해시해 저장한다")
        void sendsMailThenSavesHash() {
            when(memberRepository.findById(10L)).thenReturn(Optional.of(target));
            when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

            memberService.issueTemporaryPassword(1L, 10L);

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(mailClient).send(eq("user@test.com"), anyString(), html.capture());
            String encoded = target.getPassword();
            assertThat(encoded).startsWith("ENC:");
            String rawSent = encoded.substring("ENC:".length());
            assertThat(rawSent).hasSize(12);
            assertThat(html.getValue()).contains(rawSent);
        }

        @Test
        @DisplayName("메일 발송이 실패하면 비밀번호는 바뀌지 않는다")
        void mailFailure_leavesPasswordUntouched() {
            when(memberRepository.findById(10L)).thenReturn(Optional.of(target));
            doThrow(new BusinessException("이메일 발송에 실패했습니다."))
                    .when(mailClient).send(anyString(), anyString(), anyString());

            assertThatThrownBy(() -> memberService.issueTemporaryPassword(1L, 10L))
                    .isInstanceOf(BusinessException.class);

            assertThat(target.getPassword()).isEqualTo("OLD_HASH");
            verify(passwordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("자기 자신은 초기화할 수 없다")
        void selfReset_rejected() {
            assertThatThrownBy(() -> memberService.issueTemporaryPassword(10L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("본인");

            verify(mailClient, never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("없는 회원이면 예외")
        void unknownMember_rejected() {
            when(memberRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.issueTemporaryPassword(1L, 99L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("resetPassword — 이메일 인증 후 본인 재설정")
    class ResetPassword {

        @Test
        @DisplayName("가입 이메일이면 새 비밀번호를 해시해 저장하고 회원을 돌려준다")
        void knownEmail_updatesPassword() {
            when(memberRepository.findByEmail("user@test.com")).thenReturn(Optional.of(target));
            when(passwordEncoder.encode("newpass")).thenReturn("ENC:newpass");

            Member result = memberService.resetPassword("user@test.com", "newpass");

            assertThat(result).isSameAs(target);
            assertThat(target.getPassword()).isEqualTo("ENC:newpass");
        }

        @Test
        @DisplayName("가입되지 않은 이메일이면 예외")
        void unknownEmail_rejected() {
            when(memberRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.resetPassword("nobody@test.com", "newpass"))
                    .isInstanceOf(BusinessException.class);
            verify(passwordEncoder, never()).encode(any());
        }
    }
}
