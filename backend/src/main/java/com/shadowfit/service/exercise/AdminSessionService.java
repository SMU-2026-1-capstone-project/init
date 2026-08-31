package com.shadowfit.service.exercise;

import com.shadowfit.dto.admin.AdminSessionListItemDto;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.repository.exercise.SessionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSessionService {

    /** 페이지 크기 상한. 없으면 size=1000000 한 방으로 전체를 긁어갈 수 있다. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final SessionQueryRepository sessionQueryRepository;

    @Transactional(readOnly = true)
    public PageResponse<AdminSessionListItemDto> searchSessions(
            AdminSessionSearchCondition condition,
            AdminSessionSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = normalizeSize(size);
        return sessionQueryRepository.searchForAdmin(condition, sortKey, ascending, safePage, safeSize);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
