package com.ibmteam02.backend_auth.user.repository;

import com.ibmteam02.backend_auth.user.domain.Role;
import com.ibmteam02.backend_auth.user.domain.User;
import com.ibmteam02.backend_auth.user.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User,Long> {
    //회원가입
    boolean existsByEmailHash(String emailHash);
    //로그인
    Optional<User> findByEmailHash(String emailHash);

    // 기존 가입자(평문 데이터)를 위한 네이티브 쿼리
    @Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
    Optional<User> findByRawEmail(@Param("email") String email);

    //약사 승인 대기중인 리스트
    List<User> findByStatus(UserStatus status);

    // N+1 최적화: 승인 대기 중인 약사 + 약사 프로필(PharmacistProfile) Fetch Join 단건 쿼리 조회
    @Query("""
            SELECT u FROM User u
            JOIN FETCH u.pharmacistProfile
            WHERE u.status = :status AND u.role = :role
            """)
    List<User> findPendingPharmacistsWithProfile(
            @Param("status") UserStatus status,
            @Param("role") Role role
    );

    // N+1 최적화: 전체 회원 조회 시 건강 정보(UserProfileHealth), 기저질환(UserChronicDisease), 약사 프로필을 Fetch Join으로 함께 조회
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.userProfileHealth
            LEFT JOIN FETCH u.userChronicDiseases
            LEFT JOIN FETCH u.pharmacistProfile
            """)
    List<User> findAllWithDetails();

    long countByRole(Role role);

    long countByStatusAndRole(UserStatus status, Role role);
}
