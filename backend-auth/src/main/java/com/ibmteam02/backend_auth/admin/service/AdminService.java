package com.ibmteam02.backend_auth.admin.service;

import com.ibmteam02.backend_auth.admin.dto.AdminStatsResponse;
import com.ibmteam02.backend_auth.admin.dto.PharmacistApprovalResponse;
import com.ibmteam02.backend_auth.admin.dto.UserManagementResponse;
import com.ibmteam02.backend_auth.global.auth.service.S3Service;
import com.ibmteam02.backend_auth.global.error.exception.CustomException;
import com.ibmteam02.backend_auth.global.error.exception.ErrorCode;
import com.ibmteam02.backend_auth.user.domain.*;
import com.ibmteam02.backend_auth.user.repository.PharmacistProfileRepository;
import com.ibmteam02.backend_auth.user.repository.UserChronicDiseaseRepository;
import com.ibmteam02.backend_auth.user.repository.UserProfileHealthRepository;
import com.ibmteam02.backend_auth.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {
    private final UserRepository userRepository;
    private final PharmacistProfileRepository pharmacistProfileRepository;
    private final UserProfileHealthRepository userProfileHealthRepository;
    private final UserChronicDiseaseRepository userChronicDiseaseRepository;
    private final S3Service s3Service;

    private void validateAdminRole(String role) {
        if (!"ADMIN".equals(role) && !"ROLE_ADMIN".equals(role)) {
            throw new CustomException(ErrorCode.ADMIN_ONLY);
        }
    }

    //승인 대기 중인 약사 목록 조회 (Fetch Join으로 N+1 쿼리 제거)
    @Transactional(readOnly = true)
    public List<PharmacistApprovalResponse> getPendingPharmacists(String role) {
        // 1. 관리자 권한 체크
        validateAdminRole(role);

        // 2. WAITING_APPROVAL 상태인 약사 + 약사 프로필(2단계)을 JOIN FETCH 단건 쿼리로 조회
        return userRepository.findPendingPharmacistsWithProfile(UserStatus.WAITING_APPROVAL, Role.PHARMACIST).stream()
                .map(user -> {
                    PharmacistProfile profile = user.getPharmacistProfile();
                    return PharmacistApprovalResponse.builder()
                            .userId(user.getId())
                            .email(user.getEmail())
                            .username(user.getUsername())
                            .docNumber(profile.getDocNumber())
                            .licenseNumber(profile.getLicenseNumber())
                            .licenseImage(profile.getLicenseImage() != null
                                    ? s3Service.getPresignedUrl(profile.getLicenseImage()) : null)
                            .build();
                })
                .toList();
    }

    //약사 승인 처리
    @Transactional
    public void approvePharmacist(Long userId, String adminRole) {
        validateAdminRole(adminRole);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() != Role.PHARMACIST) {
            throw new CustomException(ErrorCode.NOT_PHARMACIST_ACCOUNT);
        }

        user.activateGeneralUser();

        pharmacistProfileRepository.findByUser(user).ifPresent(profile->{
            String imageKey = profile.getLicenseImage();

            if(imageKey != null && !imageKey.equals("DELETE_DUE_TO_PRIVACY")){
                s3Service.deleteFile(imageKey);
                profile.clearLicenseImage();
            }
        });
    }

    //약사 승인 거절
    @Transactional
    public void rejectPharmacist(Long userId, String adminRole) {
        validateAdminRole(adminRole);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() != Role.PHARMACIST) {
            throw new CustomException(ErrorCode.NOT_PHARMACIST_ACCOUNT);
        }

        user.rejectPharmacist();
    }

    //전체 회원 조회 (관리자용 - LEFT JOIN FETCH로 1 + 2N 쿼리를 단건 쿼리로 최적화)
    @Transactional(readOnly = true)
    public List<UserManagementResponse> getAllUsers(String adminRole) {
        validateAdminRole(adminRole);

        return userRepository.findAllWithDetails().stream()
                .map(user -> {
                    UserProfileHealth health = user.getUserProfileHealth();
                    List<String> diseases = user.getUserChronicDiseases() != null
                            ? user.getUserChronicDiseases().stream()
                                    .map(UserChronicDisease::getDiseaseName)
                                    .toList()
                            : List.of();

                    return UserManagementResponse.builder()
                            .id(user.getId())
                            .email(user.getEmail())
                            .username(user.getUsername())
                            .birthDate(user.getBirthDate())
                            .gender(user.getGender())
                            .role(user.getRole())
                            .status(user.getStatus())
                            .createdAt(user.getCreatedAt())
                            .isPregnant(health != null && Boolean.TRUE.equals(health.getIsPregnant()))
                            .isBreastfeeding(health != null && Boolean.TRUE.equals(health.getIsBreastfeeding()))
                            .isSmoking(health != null && Boolean.TRUE.equals(health.getIsSmoking()))
                            .isDrinking(health != null && Boolean.TRUE.equals(health.getIsDrinking()))
                            .chronicDiseases(diseases)
                            .build();
                })
                .toList();
    }

    //회원 탈퇴 (삭제)
    @Transactional
    public void deleteUser(Long userId, String adminRole) {
        validateAdminRole(adminRole);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        userRepository.delete(user);
    }

    //관리자 대시보드 명수 통계
    @Transactional(readOnly = true)
    public AdminStatsResponse getStats(String adminRole) {
        validateAdminRole(adminRole);

        long totalUser = userRepository.count();
        long pharmacistCount = userRepository.countByRole(Role.PHARMACIST);
        long pendingPharmacist = userRepository.countByStatusAndRole(UserStatus.WAITING_APPROVAL, Role.PHARMACIST);

        return AdminStatsResponse.builder()
                .totalUserCount(totalUser)
                .totalPharmacistCount(pharmacistCount)
                .pendingPharmacistCount(pendingPharmacist)
                .build();
    }

}
