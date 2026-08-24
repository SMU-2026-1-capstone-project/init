package com.shadowfit.service.report;

import com.shadowfit.dto.report.record.DailyLogRequestDto;
import com.shadowfit.dto.report.record.DailyLogResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.repository.report.DailyLogRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyLogService {
    private final DailyLogRepository dailyLogRepository;
    private final MemberRepository memberRepository;

    /**
     * 그날의 메모·기분을 저장한다 (있으면 덮어쓰기).
     *
     * <p><b>«찾아보고 없으면 save» 를 그만뒀다</b>(이슈 #215 ①). 검사와 INSERT 사이에 같은
     * {@code (member_id, log_date)} 가 들어오면 {@code uk_member_date} 위반이
     * {@code GlobalExceptionHandler} 의 {@code Exception} 핸들러로 떨어져 <b>500</b> 이 나갔다.
     * 경합 상대가 «남» 이 아니라 <b>자기 자신</b>이라(같은 회원·같은 날짜) 더블탭 한 번이면
     * 두 요청이 겹친다 — 드문 조건이 아니다.
     *
     * <p>판단과 쓰기를 DB 한 문장으로 합친다. 바로 아래 {@code accumulateStats} 가 같은 테이블에서
     * 같은 이유로 이미 그렇게 하고 있고, <b>그 주석이 catch 방식이 왜 안 되는지까지 적어뒀다.</b>
     *
     * <p>회원 존재 확인은 남긴다 — 없는 회원이면 FK 위반(500)이 아니라 404 로 답해야 한다.
     * 예전에는 이 확인이 INSERT 분기에만 있었다.
     */
    @Transactional
    public void saveOrUpdateLog(Long memberId, DailyLogRequestDto dto) {
        log.info("일지 저장 요청 - 사용자: {}, 날짜: {}", memberId, dto.getLogDate());

        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // mood 는 네이티브 쿼리라 @Enumerated(STRING) 변환이 안 걸린다 — 여기서 문자열로 넘긴다.
        dailyLogRepository.upsertMemoAndMood(memberId, dto.getLogDate(), dto.getMemo(),
                dto.getMood() == null ? null : dto.getMood().name());
    }
    /**
     * 세션 종료(완료) 시 그날의 누적 운동시간·칼로리에 반영. INSERT-또는-누적 판단과 실제 누적을
     * DB 네이티브 upsert 한 문장(ON DUPLICATE KEY UPDATE)으로 처리 — 같은 날 두 세션이 동시에
     * 종료돼도 lost-update가 안 생김. JPA save()로 먼저 시도하고 실패하면 catch해서 재시도하는
     * 방식은 Hibernate 세션이 flush 실패로 손상돼 같은 트랜잭션 내 후속 쿼리가 깨짐(실측으로 확인,
     * DailyLogServiceConcurrencyTest 최초 버전 실패 사유) — 그래서 이 방식은 쓰지 않음.
     */
    @Transactional
    public void accumulateStats(Long memberId, LocalDate logDate, int addTime, BigDecimal addCalories) {
        dailyLogRepository.upsertStats(memberId, logDate, addTime, addCalories);
    }

    @Transactional(readOnly = true)
    public DailyLogResponseDto getDailyLog(Long memberId, LocalDate date) {
        DailyLog log = dailyLogRepository.findByMemberIdAndLogDate(memberId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // 엔티티를 응답 DTO로 변환하여 반환
        return new DailyLogResponseDto(log);
    }
}
