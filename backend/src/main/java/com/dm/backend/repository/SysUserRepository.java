package com.dm.backend.repository;

import com.dm.backend.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByAccount(String account);

    boolean existsByAccount(String account);
}
