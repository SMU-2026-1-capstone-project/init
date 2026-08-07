package com.shadowfit.service.Member;

import com.shadowfit.dto.admin.AdminMemberListItemDto;
import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.repository.member.MemberQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    /** 페이지 크기 상한. 없으면 size=1000000 한 방으로 전체를 긁어갈 수 있다. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final MemberQueryRepository memberQueryRepository;

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberListItemDto> searchMembers(
            AdminMemberSearchCondition condition,
            AdminMemberSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = normalizeSize(size);
        return memberQueryRepository.searchForAdmin(condition, sortKey, ascending, safePage, safeSize);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
