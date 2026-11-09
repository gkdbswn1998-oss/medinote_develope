package com.ibmteam02.backend_auth.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ibmteam02.backend_auth.admin.dto.PharmacistApprovalResponse;
import com.ibmteam02.backend_auth.admin.dto.UserManagementResponse;
import com.ibmteam02.backend_auth.global.auth.service.S3Service;
import com.ibmteam02.backend_auth.global.error.exception.CustomException;
import com.ibmteam02.backend_auth.global.error.exception.ErrorCode;
import com.ibmteam02.backend_auth.user.domain.PharmacistProfile;
import com.ibmteam02.backend_auth.user.domain.Role;
import com.ibmteam02.backend_auth.user.domain.User;
import com.ibmteam02.backend_auth.user.domain.UserChronicDisease;
import com.ibmteam02.backend_auth.user.domain.UserProfileHealth;
import com.ibmteam02.backend_auth.user.domain.UserStatus;
import com.ibmteam02.backend_auth.user.repository.PharmacistProfileRepository;
import com.ibmteam02.backend_auth.user.repository.UserChronicDiseaseRepository;
import com.ibmteam02.backend_auth.user.repository.UserProfileHealthRepository;
import com.ibmteam02.backend_auth.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminServiceTest {

    private UserRepository userRepository;
    private PharmacistProfileRepository pharmacistProfileRepository;
    private UserProfileHealthRepository userProfileHealthRepository;
    private UserChronicDiseaseRepository userChronicDiseaseRepository;
    private S3Service s3Service;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        pharmacistProfileRepository = mock(PharmacistProfileRepository.class);
        userProfileHealthRepository = mock(UserProfileHealthRepository.class);
        userChronicDiseaseRepository = mock(UserChronicDiseaseRepository.class);
        s3Service = mock(S3Service.class);

        adminService = new AdminService(
                userRepository,
                pharmacistProfileRepository,
                userProfileHealthRepository,
                userChronicDiseaseRepository,
                s3Service
        );
    }

    @Test
    void 전체_회원_조회시_FetchJoin_단건_쿼리로_조회하며_개별_Repository를_반복_호출하지_않는다() {
        User user = User.builder()
                .email("user@test.com")
                .username("홍길동")
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        UserProfileHealth health = UserProfileHealth.builder()
                .user(user)
                .isSmoking(true)
                .isDrinking(false)
                .build();
        UserChronicDisease disease = UserChronicDisease.builder()
                .user(user)
                .diseaseName("고혈압")
                .build();

        ReflectionTestUtils.setField(user, "userProfileHealth", health);
        ReflectionTestUtils.setField(user, "userChronicDiseases", List.of(disease));

        when(userRepository.findAllWithDetails()).thenReturn(List.of(user));

        List<UserManagementResponse> result = adminService.getAllUsers("ROLE_ADMIN");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUsername()).isEqualTo("홍길동");
        assertThat(result.get(0).getIsSmoking()).isTrue();
        assertThat(result.get(0).getChronicDiseases()).containsExactly("고혈압");

        verify(userRepository, times(1)).findAllWithDetails();
        verifyNoInteractions(userProfileHealthRepository, userChronicDiseaseRepository);
    }

    @Test
    void 승인_대기_약사_목록_조회시_FetchJoin으로_프로필을_함께_가져와_N플러스1이_발생하지_않는다() {
        User pharmacist = User.builder()
                .email("pharm@test.com")
                .username("김약사")
                .role(Role.PHARMACIST)
                .build();
        ReflectionTestUtils.setField(pharmacist, "id", 2L);

        PharmacistProfile profile = PharmacistProfile.builder()
                .user(pharmacist)
                .docNumber("DOC-123")
                .licenseNumber("LIC-456")
                .licenseImage("s3-key.png")
                .build();
        ReflectionTestUtils.setField(pharmacist, "pharmacistProfile", profile);

        when(userRepository.findPendingPharmacistsWithProfile(UserStatus.WAITING_APPROVAL, Role.PHARMACIST))
                .thenReturn(List.of(pharmacist));
        when(s3Service.getPresignedUrl("s3-key.png")).thenReturn("https://presigned.url/s3-key.png");

        List<PharmacistApprovalResponse> result = adminService.getPendingPharmacists("ADMIN");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLicenseNumber()).isEqualTo("LIC-456");
        verify(userRepository, times(1))
                .findPendingPharmacistsWithProfile(UserStatus.WAITING_APPROVAL, Role.PHARMACIST);
        verifyNoInteractions(pharmacistProfileRepository);
    }

    @Test
    void 관리자가_아닌_계정이_조회하면_ADMIN_ONLY_커스텀_예외를_던진다() {
        assertThatThrownBy(() -> adminService.getAllUsers("ROLE_USER"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_ONLY);
    }

    @Test
    void 약사_승인시_존재하지_않는_회원이면_USER_NOT_FOUND_예외를_던진다() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.approvePharmacist(999L, "ROLE_ADMIN"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
