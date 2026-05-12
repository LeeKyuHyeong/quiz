package com.kh.game.security;

import com.kh.game.entity.Member;
import com.kh.game.repository.MemberRepository;
import com.kh.game.util.SecurityInputValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // 로그인 1차 방어선: 이메일 형식/SQLi 페이로드 사전 차단
        // 형식 위반 시 UsernameNotFoundException으로 변환 → FailureHandler가 "이메일 또는 비밀번호가 일치하지 않습니다"로 응답
        // (자격 정보 누출 방지를 위해 별도 메시지 사용 안 함)
        try {
            SecurityInputValidator.validateEmailOrThrow(email);
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("유효하지 않은 이메일 형식: " + e.getMessage());
        }

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));

        return new CustomUserDetails(member);
    }
}
